# Worker 컨테이너 격리와 종료 미확인 실행 복구

## 결정과 현재 상태

2026-10-13 D4의 다음 실행 경계로 **controller가 소유하는 비특권 worker 컨테이너**를 선택한다.
기존 LOCAL_JVM과 완료 기록 기반 복구는 유지한다. 이 문서의 운영 경로는 설계이며,
`WorkerContainerIsolationTest`는 PID 공간과 별도 복구 프로세스의 검증용 fixture다.
I2의 `WorkerContainerLifecycle`과 `DockerContainerEngine`은 독립 수명주기 어댑터로 구현했으며
중앙 executor/접수 자동 해제에는 아직 연결하지 않았다.
운영 container executor, sequence의 외부 DB 연결 모드, 종료 미확인 접수 자동 해제는 아직 구현하지 않았다.

관찰 기반 `WorkerProcessTree`는 관찰 전에 부모와 분리된 프로세스와 controller 사망 후의
프로세스를 포괄하지 못한다. PID가 없어졌거나 Docker 조회가 비었다는 사실만으로 이
LOCAL_JVM 실행의 완료 기록을 대신할 수 없다. 컨테이너 회수 증거도 기존 marker의 의미를 바꾸지 않는다.

Linux PID namespace의 init이 종료되면 커널은 그 공간의 다른 프로세스도 종료한다.
부모가 종료된 프로세스의 재부모화와 별도 세션 생성은 PID 공간을 벗어나는 조건이 아니다.
이 성질을 controller가 프로세스 목록을 관찰하지 못한 경우의 종료 경계로 사용한다.
근거: [Linux PID namespace 명세](https://man7.org/linux/man-pages/man7/pid_namespaces.7.html).

Docker는 실행별 PID 공간과 사용자·capability·읽기 전용 파일 시스템·자원 한도를 함께
설정할 수 있다. Linux host 또는 Docker Desktop의 Linux 엔진에서 같은 제어 경로를
검증하기 위해 이 방식을 선택한다. `--privileged`, host PID, Docker 소켓 전달은 사용하지 않는다.
근거: [Docker 실행 설정](https://docs.docker.com/engine/containers/run/).
이는 신뢰한 배포물의 수명주기를 관리하는 설계이며 적대적 코드에 대한 VM 수준 보장은 아니다.

## 책임 분리

| 구성 요소 | 책임 |
| --- | --- |
| 중앙 runtime | 실행 슬롯·RunStore 잠금·영속 marker·프로토콜·접수 재개 판단 |
| container 실행 어댑터 | 이미지/배포 검증, 실행 경계 생성·시작·중지·회수, 단계별 영속 소유권 |
| 모듈별 자원 어댑터 | 실행별 MySQL/Redis 생성·접속 정보 전달·소유 자원 정리 |
| worker | 전달된 접속 정보로 시나리오 실행, 공통 protocol/check/metric/report 출력 |

worker에는 Docker 소켓·Docker 접속 자격·controller 저장소를 주지 않는다. 현재 sequence는
자신이 Testcontainers를 시작하므로 controller의 DB 준비와 worker의 실행을 먼저 분리해야 한다.
일반 runtime에 sequence 분기를 넣지 않고 모듈이 자원 준비/정리 정책을 제공한다.
기존 자체 DB 생성 LOCAL_JVM 실행은 호환 경로로 유지한다.

컨테이너 transport는 기존 protocol v1과 공통 evidence sink를 재사용한다. TTY를 사용하지
않고 stdout protocol과 stderr를 구분하며 기존 frame/total/event/deadline 한도를 유지한다.
worker의 Java/OS 정보는 HELLO에서 기록한다. controller 정보를 복사하지 않는다.
execution kind와 capability는 실제 어댑터가 등록될 때만 추가하고, 준비 전 UI에서 노출하지 않는다.

## 시작과 재시작의 영속 경계

1. RunStore 잠금과 실행 슬롯을 획득하고 새 버전의 차단 기록을 먼저 force한다.
   Run·배포 hash·이미지 ID·엔진 ID·소유권 UUID·결정적 컨테이너 이름을 기록한다.
   worker/DB/네트워크 등 자원별 생성 의도와 상태는 개수·크기 상한을 둔다.
2. 컨테이너를 **정지 상태로 생성**한다. 응답의 전체 ID와 실제 설정을 검증하고 기록한 뒤 시작한다.
   프로세스가 먼저 실행되고 나중에 경계에 편입되는 구간을 만들지 않는다.
3. 시작 시도 직전의 기록을 남긴 뒤 시작한다. 정상 결과도 EOF·exit·증거 저장·자원 정리가
   끝나기 전에는 성공 또는 새 접수 허용으로 처리하지 않는다.
4. controller 재시작 시 기존 Run 복원 후 소유 엔진·전체 ID·이름·라벨·이미지·격리 설정을
   모두 확인한다. 별도 프로세스가 worker 경계를 정지·삭제하고 외부 DB/네트워크도 회수한다.
5. **create/start 요청의 완료가 불확실하면 자동 해제하지 않는다.** 결정적 이름이나 빈 목록만으로
   지연 요청을 차단했다고 추정하지 않는다. 재시작 정책은 `no`지만 그것만으로 이미 전송된 start의
   완료를 증명하지 않는다. 불확실한 단계는 운영자 확인을 유지한다.
6. 모든 경계의 종료와 자원 회수를 확인한 경우에만 새 버전 기록의 정책에 따라 접수 차단을 해제한다.
   중단 Run의 실패·부분 증거는 유지하고 작업을 자동 재실행하지 않는다.

기존 v1 marker에는 실행 전 컨테이너 경계 증거가 없으므로 이 경로로 승격하지 않는다.
손상/링크/초과 기록, 엔진 변경, 다른 배포물, 소유권 모호함, Docker 장애는 차단을 유지한다.
OS 호스트 전원 장애의 디렉터리 fsync·디스크 내구성은 별도 검증 범위다.

## 초기 실행 설정

운영 기본값은 어댑터 구현 시 확정한다. 배포 이미지 ID와 jar hash를 서버 설정에서 고정하고,
worker는 non-root, capability 전체 제거, no-new-privileges, 읽기 전용 rootfs, restart=no,
전용 PID 공간과 제한된 임시 쓰기 영역을 사용한다. CPU·메모리·PID·실행 시간·출력·artifact
한도를 서버에서 제한한다. HTTP 시나리오 입력으로 mount/이미지/명령/권한을 받지 않는다.
DB가 필요한 실행에는 해당 Run 전용 네트워크와 접속 정보만 제공하고 별도 포트 공개는 하지 않는다.
이 네트워크·artifact 인계·자원 소유권의 세부 계약은 후속 구현에서 함께 검증한다.

## 검증 fixture

```bash
# 로컬에 busybox:1.37이 있어야 하며 테스트는 이미지를 자동으로 받지 않는다.
./gradlew test workerContainerIsolationTest
```

별도 task로 실행하며 일반 `test`, `careerSequenceTest`, privileged daemon 시험과 분리한다.
이 fixture는 로컬 BusyBox의 실제 이미지 ID를 고정한다. 사용자 65534, 메모리 64 MiB,
CPU 0.5개, PID 32개, network=none, read-only, cap-drop=ALL, no-new-privileges,
restart=no, 로그 저장 없음, 1 MiB `/tmp` tmpfs를 사용한다. host bind·socket·volume은 없다.
Docker 응답은 16 KiB/20초, 시험 controller 시작은 30초, 회수 JVM 대기는 60초다.

중간 부모를 종료하고 `setsid`로 생성한 프로세스가 PPID 1, 별도 session ID를 가지는 것을
확인한다. 시험 controller를 강제 종료한 뒤에도 해당 프로세스가 살아 있음을 확인한다.
다음 두 경우를 새로운 JVM으로 회수한다.

- 컨테이너와 분리 프로세스가 계속 실행 중인 경우
- PID 공간 init을 SIGKILL하여 Docker가 stopped/Pid=0으로 보고한 경우

회수 JVM은 동일 엔진·이미지·이름·UUID·전체 ID·격리 설정을 확인한 후 대상 컨테이너만
삭제하고 부재를 확인한다. 다른 UUID의 비교 컨테이너는 계속 실행 중이어야 한다.
완료 기록 없는 기존 marker는 바이트 그대로 남아야 한다. 이는 생산 복구 경로를 호출하는
통합 시험이 아니라 운영 경계 선택을 위한 실제 엔진 검증이다.

finally에서도 두 컨테이너 각각의 소유권을 대조해 정리하고 ID 부재를 확인한다.
시험 JVM/호스트가 강제 종료되면 finally가 실행되지 않을 수 있으므로 출력의 전체 ID와
`engineering-lab.isolation-test` UUID를 대조해 해당 자원만 조사한다. 전체 prune은 하지 않는다.
기존 BusyBox 이미지는 유지한다. Docker metadata 부재 이상의 호스트 커널 내부 검사나
실제 Java 21 protocol·MySQL·daemon 재시작·늦은 API 요청 검증은 이 fixture에 포함하지 않는다.

## 독립 구현 단위

| 단위 | 완료 조건 | 상태 |
| --- | --- | --- |
| I1 | 비특권 경계·분리 프로세스·controller kill·새 회수 프로세스·비교 자원 보존 검증 | 검증 2개 통과 |
| I2 | 생성 전 영속 의도·생성 후 ID 기록·불확실 요청 차단을 포함한 container lifecycle 어댑터 | 구현, 별도 어댑터 검증 |
| I3 | 실제 Java 21 protocol transport·artifact·종료/증거 저장과 중앙 executor 연결 | 예정 |
| I4 | sequence의 DB 소유자를 controller로 분리하고 기존 LOCAL_JVM 호환 검증 | 예정 |
| I5 | 새 격리 기록 기반 중단 실행 복구·동일 저장소 재시작·접수 재개·장애 주입 | 예정 |

I1만으로 D4 전체 복구 또는 종료 미확인 실행의 자동 복구를 완료로 표시하지 않는다.

## I2 수명주기 어댑터 계약

서버가 관리하는 실행별 기존 디렉터리와 `Spec`(Run ID, 배포 SHA-256, 이미지 ID, 명령)을
`WorkerContainerLifecycle`에 전달한다. 생성자는 `.lock` 파일의 배타 잠금을 종료까지
유지하며 중복 controller와 링크 잠금 파일을 거부한다. HTTP 입력으로 생성하지 않는다.
중앙 RunStore 잠금과 실행 슬롯 확보는 후속 executor의 책임이다.

`create()`는 `intent.json`을 먼저 force하고 정지 상태의 컨테이너를 만든다. 실제 설정과
이미지·이름·Run/배포/UUID 라벨을 검사하고 `created.json`에 전체 ID를 force한 후 반환한다.
`start()`는 `starting.json`을 먼저 force하며 응답과 소유권 확인 후 `started.json`을 남긴다.
기록은 append-only 단계별 파일이며 각각 최대 4 KiB, 최대 6개다. 모든 후속 기록은 최초
intent 바이트의 SHA-256에 결합한다. 부분 쓰기·순서 오류·링크·다른 배포/명령·기록 초과는
차단한다. 기존 파일을 덮어쓰거나 정리하면서 삭제하지 않는다. 디렉터리 fsync를 포함한
호스트 전원 장애 내구성은 보장하지 않는다.

| 재개방 상태 | 의미 / 동작 |
| --- | --- |
| EMPTY | Docker 변경 요청 전, 복구 불필요 |
| CREATE_UNCONFIRMED | 생성 의도만 존재, 생성 재요청·자동 회수 금지 |
| CREATED | 전체 ID가 확인된 정지 경계, 시작 또는 회수 가능 |
| START_UNCONFIRMED | 시작 의도만 존재, 시작 재요청·자동 회수 금지 |
| STARTED | 시작 응답과 소유권 확인 완료, 회수 가능 |
| REMOVING | 회수 의도 저장 후 결과 미확인, 정확한 ID를 다시 조사해 회수 재개 |
| REMOVED | 같은 엔진에서 부재 확인 완료, 재확인만 수행 |

`reconcile()`는 엔진 ID를 전후로 확인하고 전체 ID의 이름·라벨·이미지·격리 설정을 검사한다.
확인된 대상만 강제 삭제하며, 빈 결과는 성공한 같은 엔진 조회에서만 인정한다.
삭제 후 응답 유실은 다음 재개방에서 부재를 확인할 수 있다. 생성/시작 미확인은 Docker를
조회해 추정하지 않고 `REQUIRES_OPERATOR`를 반환한다. 중앙 marker, Run 결과와 증거를
수정하거나 해제하는 API는 없다. 작업 재실행도 하지 않는다.

`isolated-v1` 프로파일은 사용자 65534, read-only rootfs, cap-drop=ALL,
no-new-privileges, 전용 PID/IPC, network=none, restart=no, memory=256 MiB, CPU 1개,
PID 64개, 로그 저장 없음, 16 MiB `/tmp` tmpfs다. 명령은 최대 32개 인자·인자당 1024문자·
총 2048문자이며 기록의 UTF-8 크기 상한도 적용한다. 이미지 ID만 허용하고 pull은 하지 않는다.
이미지 entrypoint는 등록된 명령으로 대체하며 생성 후 실제 command도 대조한다.
호스트 mount/소켓·공유 namespace·장치·추가 capability·포트 바인딩을 허용하지 않는다.
이는 DB 연결 전 단계의 고정 프로파일이며 I4에서 전용 네트워크 계약을 별도로 추가해야 한다.

Docker CLI는 응답당 최대 64 KiB·30초, 직접 프로세스/IO 종료 대기는 각각 2초다.
파일 검사/force와 OS 호출 자체의 강제 중단 시간은 보장하지 않는다. CLI timeout은 Docker
서버 요청의 취소 증거가 아니므로 생성·시작 상태를 확정하지 않는다. 응답 오류의 전체 출력은
예외에 복사하지 않는다. Docker 환경은 controller가 신뢰하는 로컬 설정을 사용한다.

`WorkerContainerLifecycleTest`는 Docker 대역으로 영속 기록 순서·응답 유실·동일 ID 삭제·
변조/격리 오류·배타 잠금·재개방을 검증한다. `workerContainerIsolationTest` task에는
`WorkerContainerLifecycleIntegrationTest`도 포함한다. 이 검증은 실제 어댑터로 만든
정지/실행 컨테이너를 새 JVM이 영속 기록만으로 회수하고 반복 회수와 자원 부재를 확인한다.
기존 I1의 비교 자원 보존 검증도 함께 실행한다.
