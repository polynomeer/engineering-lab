# Sequence 컨테이너 소유권과 복구 명령

이 문서는 D4의 **실행별 컨테이너 회수 단위**다. 기존 [접수 차단 기록과 프로세스 복구](worker-recovery.md)를 유지한다.
서버 시작 시 자동 복구·접수 재개는 아직 연결하지 않았다. 관리자가 controller를 정지하고
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
정리 완료를 뜻하지 않는다. 서버 접수 차단 기록은 **자동 해제하지 않는다**. 남은 프로세스·관련 자원을
확인하고 [기존 운영자 해제 절차](worker-recovery.md#운영자-복구-절차)를 따라야 한다.

worker 강제 종료 직전 Docker에 전달된 생성 요청이 뒤늦게 반영될 수 있다. daemon이 안정된 뒤
동일 명령을 다시 실행해 확인한다. 컨테이너의 임의 라벨 변경·이름 변경, Docker 데이터 디렉터리 복제,
잠금 파일 교체·다른 호스트로의 workspace 복사, 비협조적인 프로세스는 자동 복구 보장 범위 밖이다.
이름 변경 등 관측된 불일치는 삭제를 거부한다. 토큰 라벨 자체가 제거된 자원은 검색할 수 없으므로
관리자의 별도 조사가 필요하다. Docker 관리자에 대한 보안 격리 기능이 아니다.

기록이 없거나 불완전한 이전 실행, Docker 연결 확인 전에 실패한 실행은 기존 수동 절차를 따른다.
기록의 디렉터리 fsync까지 포함한 전원 장애 내구성은 보장하지 않는다. 실제 Docker daemon 재시작·
호스트 장애 주입, 서버 시작 시 재조정과 접수 재개, OS 수준 프로세스 격리는 후속 검증·구현 범위다.
