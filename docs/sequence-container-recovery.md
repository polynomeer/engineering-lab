# Sequence 컨테이너 소유권과 복구 명령

이 문서는 D4의 **실행별 컨테이너 회수 단위**다. 기존 [접수 차단 기록과 프로세스 복구](worker-recovery.md)를 유지한다.
종료 확인 기록이 있는 실행은 아래 opt-in 시작 시 복구를 사용할 수 있다. 그 외에는 관리자가 controller를 정지하고
대상 workspace와 Run을 확인한 뒤 복구 명령을 실행하면, 해당 실행의 MySQL 컨테이너를 찾아 회수한다.

## 기록과 실행 경계

sequence worker는 workspace의 `sequence-container.lock`을 실행 종료까지 배타 잠금한다.
복구도 같은 잠금을 얻어야 하며 살아 있는 worker와 동시 실행할 수 없다. 잠금 파일을
삭제하거나 교체하면 보호가 깨지므로 그대로 보존한다. workspace와 상위 경로는 관리자 소유여야 한다.

MySQL 생성 요청 **전에** `sequence-container.properties`를 배타 생성하고 파일 쓰기를 force한다.
기록은 최대 4 KiB, version 1이며 Run ID·배포 SHA-256·Docker 엔진 ID·임의 UUID 토큰을 담는다.
토큰으로 컨테이너 이름 `engineering-lab-sequence-<token>`을 정하고 다음 라벨을 함께 지정한다.

| 라벨 | 값 |
| --- | --- |
| `engineering-lab.run-id` | Run ID |
| `engineering-lab.owner-token` | 기록의 UUID |
| `engineering-lab.artifact-sha256` | worker jar SHA-256 |
| `engineering-lab.resource` | `sequence-mysql-v1` |

기록에 DB 비밀번호·Docker 인증정보를 저장하지 않는다. 정상 종료·복구 후에도 조사와 재검증을 위해
기록과 잠금 파일을 보존한다. worker는 같은 workspace에서 기록을 덮어써 새 실행을 시작하지 않는다.
기존 protocol과 시나리오 입력은 바꾸지 않으며 소유권을 HTTP 파라미터로 지정할 수 없다.

## controller 정지 후 실행

1. 기존 `.active-sequence-worker.properties`의 Run ID와 Run 이력을 확인하고 controller를 정지한다.
2. 해당 Run의 worker workspace를 찾는다. 실패 요약의 `worker.workspace` 또는
   `worker-workspaces/worker-*/sequence-container.properties`의 Run ID를 대조한다.
   자동으로 모든 workspace를 순회하거나 일괄 정리하지 않는다.
3. 해당 workspace의 신뢰하는 `worker.jar`와 기록된 배포 해시가 일치하는지 확인한다.
   새로 빌드한 jar가 아니라 **해당 실행의 배포물**을 사용한다. Java 21과 원래 Docker 연결 환경이 필요하다.
4. 아래 경로와 Run ID를 확인한 실제 값으로 바꿔 실행한다. 임의 Java 옵션을 주입하는
   `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `CLASSPATH`는 해제한다.

```bash
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS -u CLASSPATH \
  /absolute/jdk21/bin/java -Xmx256m \
  -jar /absolute/workspace/worker.jar \
  --recover /absolute/workspace <run-id>
```

성공 시 stdout은 다음과 같은 작은 JSON이며 종료 코드는 0이다.

```json
{"status":"RECOVERED","runId":"...","removedContainers":1,"admissionReleased":false}
```

이미 회수된 경우 `removedContainers`는 0이다. 오류는 비정상 종료와 stderr로 확인한다.
전용 복구 프로세스는 Docker 초기화·조회·삭제를 포함해 60초 제한을 적용하며, 초과 시 종료 코드 21로
강제 종료한다. 이 경우 Docker 요청의 결과가 불명확할 수 있으므로 기록을 유지하고 같은 명령으로 다시 확인한다.

## 삭제 전후 검사

복구는 기록의 형식·크기·Run·현재 실행 jar 해시를 먼저 검사한다. Docker 엔진 ID가 기록과
다르면 거부한다. 토큰 라벨로 최대 2개까지 조회하고, 2개가 발견되면 모호한 소유권으로 거부한다.
예상 자원은 MySQL 한 개다. 발견된 컨테이너의 전체 ID·이름·이미지 `mysql:8.0.39`·모든 소유권
라벨을 다시 확인한 후 해당 ID만 강제 삭제하고 익명 볼륨도 회수한다. 전역 prune이나 Run ID만을
기준으로 한 삭제는 하지 않는다. Ryuk의 동시 삭제로 이미 사라진 자원은 허용한다.
삭제 후 토큰 조회가 비었는지와 엔진 ID를 다시 확인한다. 불일치·Docker 불가·삭제 실패·잔존 자원은 성공으로 보고하지 않는다.

## 성공 이후에도 필요한 확인

`RECOVERED`는 조회 시점에 해당 토큰의 컨테이너가 없다는 뜻이다. 중앙 Run 성공이나 모든 자원
정리 완료를 뜻하지 않는다. 이 수동 명령 자체는 서버 접수 차단 기록을 **해제하지 않는다**. 남은 프로세스·관련 자원을
확인하고 [기존 운영자 해제 절차](worker-recovery.md#운영자-복구-절차)를 따라야 한다.

worker 강제 종료 직전 Docker에 전달된 생성 요청이 뒤늦게 반영될 수 있다. daemon이 안정된 뒤
동일 명령을 다시 실행해 확인한다. 컨테이너의 임의 라벨 변경·이름 변경, Docker 데이터 디렉터리 복제,
잠금 파일 교체·다른 호스트로의 workspace 복사, 비협조적인 프로세스는 자동 복구 보장 범위 밖이다.
이름 변경 등 관측된 불일치는 삭제를 거부한다. 토큰 라벨 자체가 제거된 자원은 검색할 수 없으므로
관리자의 별도 조사가 필요하다. Docker 관리자에 대한 보안 격리 기능이 아니다.

기록이 없거나 불완전한 이전 실행, Docker 연결 확인 전에 실패한 실행은 기존 수동 절차를 따른다.
기록의 디렉터리 fsync까지 포함한 전원 장애 내구성은 보장하지 않는다. 실제 Docker daemon 재시작·
호스트 장애 주입, 종료 미확인 실행의 자동 접수 재개, OS 수준 프로세스 격리는 후속 검증·구현 범위다.

## 종료 확인 기록이 있는 실행의 시작 시 복구

`lab.career.sequence.recover-on-startup=true`로 명시적으로 켠 경우에만, 서버는 RunStore의
배타 소유권을 얻고 중단 Run 상태를 복원한 뒤 등록 과정에서 한 번 복구를 시도한다.
기본값은 `false`다. HTTP 준비 상태 조회나 실행 요청은 복구를 실행하지 않는다.

controller는 worker의 RESULT 수신뿐 아니라 EOF·정상 종료 코드·증거 flush·관찰된 자손과
입출력 스레드 정리가 끝난 뒤 `.active-sequence-worker.properties.completed`를 배타 생성한다.
최대 4 KiB이며 접수 차단 기록 전체의 SHA-256, workspace 이름, worker 결과 상태·사유를 담는다.
worker summary의 임의 값이나 PID를 종료 증거로 사용하지 않는다. 정상 성공에서는 두 파일을 제거하며,
그 직전 controller가 중단되거나 실험이 실패하면 재시작 판단 자료로 남는다.

자동 해제 조건은 모두 충족해야 한다.

- 현재 배포 hash와 기존 marker가 같고, 완료 기록이 정확히 그 marker에 속한다.
- workspace는 등록 경로 바로 아래의 실제 디렉터리이며 링크나 상위 경로 참조가 아니다.
- RunStore에 해당 sequence Run의 종료 기록이 있다. 시작 시 INTERRUPTED로 복원된 Run도 포함한다.
- worker 결과가 `SUCCEEDED`이거나 `FAILED / EXPECTATION_NOT_MET`이다. 후자는 실험 기대값 불일치이며
  현재 sequence 배포 코드에서 DB 시작·측정·보고서 처리·context/container 정리가 반환된 경로다.
  일반 준비 실패, 컨테이너 정리 실패, 취소, 통신 오류는 이 조건에 해당하지 않는다.
- 검증한 같은 jar의 별도 `--recover`가 소유권·엔진·컨테이너 정리를 확인하고, 정확한 Run ID의
  `RECOVERED` 응답과 종료 코드 0을 반환한다. stdout은 최대 4 KiB, 응답 대기는 최대 60초다.
  정상 응답·실패·시간 초과·인터럽트 모두에서 직접 복구 프로세스와 관찰된 자손을 정리한다.
  50ms 간격으로 최대 128개 identity를 추적하고 신호 전에 시작 시각을 대조한다.
  관찰 스레드 종료, 정상 종료 신호, 강제 종료 신호에 각각 최대 2초, IO 종료에 추가 2초를 기다린다.
  정리가 확인되지 않으면 정상 응답·종료 코드 0이어도 접수 차단을 유지한다.
  관찰 전 분리된 자손과 controller 강제 종료 후 고아 프로세스까지 격리하는 보장은 아니다.
- 복구 후에도 marker 소유권이 같고 controller 스레드가 중단되지 않았다.

복구 성공 시 완료 기록을 먼저 제거하고 marker를 제거한다. 두 삭제 사이의 장애는 접수 차단을 유지한다.
원래 Run의 성공/실패, CHECK, 보고서와 workspace 소유권 기록은 변경하지 않는다.
새 실행은 기존 Docker readiness 검사도 통과해야 한다. 로그의 `stage=worker_startup_recovery`와
`result=NOT_REQUIRED|RECOVERED|REQUIRES_OPERATOR|FAILED`로 시작 시 결과를 확인한다.

완료 기록이 없는 강제 종료, 구버전 기록, 손상·불일치, 배포 교체, Docker 불가에서는 차단을 유지한다.
자동 재시도하지 않으므로 원인을 조사한 뒤 재시작하거나 운영자 절차를 따른다.
이 범위는 정상 종료가 확인된 실행의 재조정이다. 임의의 controller/worker 강제 종료 후 자동 해제,
뒤늦은 Docker 생성 요청, 관찰 전 분리된 프로세스, 실제 daemon/호스트 장애와 전원 장애 내구성까지
해결한 것으로 해석하지 않는다. 복구 CLI 자체는 현재 프로세스 생성이 없는 신뢰하는 배포물에 한정한다.

## Docker 통신 장애 회귀 검증

`SequenceRecoveryTransportTest`는 실제 Java 21 sequence 배포 jar와 중앙 복구 실행기를 사용하되,
Docker API는 루프백의 테스트 전용 HTTP 서버로 대체한다. 실행마다 별도 controller 프로세스와
저장소를 사용하며, 장애 후 새 프로세스에서 같은 저장소를 다시 열어 복구한다.
공유 Docker 엔진을 중단하거나 다른 세션의 컨테이너를 변경하지 않는다.

| 주입한 장애 | 첫 복구에서 확인할 결과 | 정상 응답 복원 후 확인할 결과 |
| --- | --- | --- |
| 컨테이너 목록 조회 503 | marker·완료 기록 보존, 삭제 요청 없음 | 소유 자원 정리 후 접수 차단 해제 |
| 컨테이너 목록 조회 무응답 | 실제 60초 복구 제한으로 실패, 차단 유지 | 새 복구 프로세스에서 성공 |
| 컨테이너 삭제 503 | 자원 잔존, 차단 유지 | 삭제 확인 후 해제 |
| 삭제 반영 후 응답 연결 유실 | 자원이 없어도 첫 복구는 실패 | 빈 목록 확인 후 해제, 다음 프로세스에서 재삭제하지 않음 |
| 삭제 후 Docker 엔진 ID 변경 | 정리된 것으로 확정하지 않고 차단 유지 | 원래 엔진 식별자로 복원된 뒤 재검증 |

모든 경우 소유권 journal을 보존하고 원래 Run의 실패 상태·사유가 유지되어야 한다.
목록 요청은 소유권 토큰과 최대 2개 제한, 삭제는 정확한 전체 ID와 force/volume 플래그를 검사한다.
테스트 서버의 요청 처리도 총 64개·본문 4 KiB로 제한하며 예상하지 않은 API는 거부한다.
Testcontainers의 종료 시 세션 라벨 조회·정리 요청은 해당 세션 필터가 있는 경우에만 빈 결과를 돌려준다.

```bash
./gradlew careerSequenceTest --tests '*SequenceRecoveryTransportTest'
```

기존 Career 배포 jar·manifest와 Java 21/25가 필요하다. 테스트 전용 자식 프로세스에서만
`DOCKER_HOST`를 루프백으로 지정하고 Ryuk·시작 검사를 비활성화한다. 일반 worker 배포의
설정이나 Ryuk 필수 조건은 바꾸지 않는다. 장애가 지정한 HTTP 지점에 실제로 도달했는지도
검증하므로, 다른 Docker 연결로 우회한 실행을 성공한 장애 검증으로 인정하지 않는다.

이 검증은 실제 클라이언트의 HTTP 오류·연결 유실·정지 처리에 대한 증거다. 실제 Docker
엔진 재시작, 디스크 손상, 호스트 전원 장애, 지연된 생성 요청, OS 수준 프로세스 격리의
검증은 아니다. 실제 엔진 재시작은 다음 절의 별도 검증으로 확인하며, 나머지는 후속 작업으로 남는다.

## 독립 Docker 엔진 재시작 검증

`SequenceDaemonRecoveryTest`와 `careerDaemonRecoveryTest`는 실제 엔진 강제 종료·재시작을
검증하는 별도 opt-in fixture다. **2026-10-13 명시적 승인 후 실제 검증 1개를 통과했다.**
일반 `test`, `careerSequenceTest`에서는 실행하지 않는다. Gradle 옵션과 테스트 내부 플래그가
모두 있어야 컨테이너 생성 단계에 진입할 수 있다.

fixture는 `docker:27.5.1-dind` 컨테이너 하나 안에 독립 Docker 엔진을 만든다.
호스트 디렉터리·Docker 소켓을 마운트하지 않으며 API 포트는 `127.0.0.1`의 임의 포트에만 공개한다.
메모리 1 GiB, CPU 1개, PID 256개 제한을 요청한다. `/run`은 16 MiB tmpfs로 두어
강제 종료 후 오래된 PID 파일을 다음 시작에 재사용하지 않는다. 엔진 데이터 익명 볼륨은 재시작 동안 유지한다. 하지만 `--privileged`는 넓은 호스트 권한을
부여하므로 승인된 실험 환경에서만 실행해야 한다. Docker-in-Docker는 여기서 테스트 장치이며
운영 worker의 보안 격리 방식으로 채택한 것이 아니다.

검증한 순서는 다음과 같다.

1. 전용 엔진 안에 소유권 라벨을 가진 자원과 다른 토큰의 비교 자원을 만든다.
2. 종료 확인 기록·소유권 journal을 합성하고 실제 RunStore에 실패 Run을 저장해 완료 이후의 영속 경계를 재현한다.
3. 전용 엔진 컨테이너에 SIGKILL을 보내고, 복구 실패가 marker·완료 기록을 보존하는지 확인한다.
4. 동일 컨테이너와 데이터로 엔진을 다시 시작해 엔진 ID와 컨테이너 metadata가 유지되는지 확인한다.
5. 실제 Java 21 배포 복구 명령으로 소유 자원만 제거하고, 비교 자원과 원래 Run·journal을 보존하는지 확인한다.
6. UUID 라벨과 전체 ID를 대조한 뒤 테스트 엔진과 그 익명 볼륨을 제거하고, 각각의 ID가 조회되지 않는지 확인한다.

작은 `busybox:1.37` 이미지를 전용 엔진 안에서만 `mysql:8.0.39` 이름으로 붙여 sleep 프로세스를
실행한다. 따라서 이 검증은 실제 Docker 자원·metadata 복구를 대상으로 하며 MySQL 데이터
복구 실험은 아니다. 기존 실제 MySQL 실행 검증은 `careerSequenceTest`를 사용한다.

실행 승인을 받은 환경에서만 아래 명령을 사용한다. 테스트 자체는 이미지를 자동으로 받지 않는다.

```bash
docker pull docker:27.5.1-dind
docker pull busybox:1.37
./gradlew careerDaemonRecoveryTest -PlabAllowPrivilegedDaemonTest=true
```

기존 Career 배포 jar·manifest와 Java 21/25, Docker CLI가 필요하다. Docker 명령 응답은
16 KiB·30초, 엔진 준비 대기는 90초, 중앙 복구 자식 프로세스 대기는 80초로 제한한다.
개별 명령 정리 시간이 추가될 수 있다. 테스트 결과는 `build/reports/tests/careerDaemonRecoveryTest/`에 남는다.

JVM/호스트 자체가 종료되면 finally 정리가 실행되지 않을 수 있다. 테스트 출력의
`owner_token`, `container_id`, `volume_ids`를 보관한다. 정상 정리 확인은
`stage=private_daemon_cleanup result=CONFIRMED`로 기록된다. 잔존 여부는 아래와 같이 **조회**하고, 해당 테스트의
UUID·전체 ID·생성 시각을 대조한 뒤 승인된 대상 하나만 정리한다. 공유 엔진 전체의 prune이나
라벨만으로 일괄 삭제하지 않는다.

```bash
docker ps -a --filter label=engineering-lab.daemon-test --format '{{.ID}} {{.Labels}} {{.Status}}'
```

검증 중 이미지 전달 경로를 컨테이너 루트로 변경하고, 준비 확인에서 오류 문구를 엔진 ID로
인정하지 않도록 UUID 형식을 검사했다. 강제 종료 후 containerd의 PID 파일이 남아 재시작을
막는 문제는 위 `/run` tmpfs로 해결했다. 최종 실행은 실패·오류·건너뜀 0이며
`./gradlew test`도 성공했다(루트 222개 기존 성공 결과 재사용). 전용 컨테이너·익명 볼륨은
테스트의 finally에서 삭제 후 부재를 확인했다. 이번 검증용으로 받은 `docker:27.5.1-dind`
이미지도 검증 종료 후 제거했고, 기존 `busybox:1.37` 캐시는 유지했다.

완료 기록 없는 worker의 자동 접수 해제, 호스트 전원 장애,
DB 일관성 복구, 관찰 전 분리된 프로세스의 OS 격리를 검증한 것으로 간주하지 않는다.
