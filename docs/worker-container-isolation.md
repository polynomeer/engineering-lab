# Worker 컨테이너 격리와 종료 미확인 실행 복구

## 결정과 현재 상태

2026-10-13 D4의 다음 실행 경계로 **controller가 소유하는 비특권 worker 컨테이너**를 선택한다.
기존 LOCAL_JVM과 완료 기록 기반 복구는 유지한다. 이 문서의 운영 경로는 설계이며,
`WorkerContainerIsolationTest`는 PID 공간과 별도 복구 프로세스의 검증용 fixture다.
I2의 `WorkerContainerLifecycle`과 `DockerContainerEngine`을 I3의 `ContainerWorkerProcess`와
명시적 `ContainerWorkerExecutor`에 연결했다. 실제 Java 21 fixture로 중앙 증거 저장과
회수를 검증하며 기본 운영 등록은 활성화하지 않는다. I4에서 실제 sequence의 controller 소유 DB 연결도 검증했다.
I5에서 CONTAINER 선택 등록과 새 기록 기반 시작 시 복구를 구현했다. 기본값은 LOCAL_JVM이며 복구는 opt-in이다.

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

worker에는 Docker 소켓·Docker 접속 자격·controller 저장소를 주지 않는다. I4 sequence 컨테이너 경로는
controller의 DB 준비와 worker 실행을 분리한다.
일반 runtime에 sequence 분기를 넣지 않고 모듈이 자원 준비/정리 정책을 제공한다.
기존 자체 DB 생성 LOCAL_JVM 실행은 호환 경로로 유지한다.

컨테이너 transport는 기존 protocol v1과 공통 evidence sink를 재사용한다. TTY를 사용하지
않고 stdout protocol과 stderr를 구분하며 기존 frame/total/event/deadline 한도를 유지한다.
worker의 Java/OS 정보는 HELLO에서 기록한다. controller 정보를 복사하지 않는다.
I3의 execution kind는 `CONTAINER`이며 capability와 UI 노출은 실제 운영 등록이 준비된 뒤 추가한다.

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
artifact 인계는 아래 I3 계약을 따르며 네트워크·모듈 자원 소유권은 아래 I4 계약을 따른다.

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
| I3 | 실제 Java 21 protocol transport·artifact·종료/증거 저장과 중앙 executor 연결 | 구현, 명시적 테스트 등록 |
| I4 | sequence의 DB 소유자를 controller로 분리하고 기존 LOCAL_JVM 호환 검증 | 구현 |
| I5 | 새 격리 기록 기반 중단 실행 복구·동일 저장소 재시작·접수 재개·장애 주입 | 구현·검증 완료 |

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
이는 DB 연결 전 단계의 고정 프로파일이며 sequence는 아래 I4 전용 네트워크 프로파일을 사용한다.

Docker CLI는 응답당 최대 64 KiB·30초, 직접 프로세스/IO 종료 대기는 각각 2초다.
파일 검사/force와 OS 호출 자체의 강제 중단 시간은 보장하지 않는다. CLI timeout은 Docker
서버 요청의 취소 증거가 아니므로 생성·시작 상태를 확정하지 않는다. 응답 오류의 전체 출력은
예외에 복사하지 않는다. Docker 환경은 controller가 신뢰하는 로컬 설정을 사용한다.

`WorkerContainerLifecycleTest`는 Docker 대역으로 영속 기록 순서·응답 유실·동일 ID 삭제·
변조/격리 오류·배타 잠금·재개방을 검증한다. `workerContainerIsolationTest` task에는
`WorkerContainerLifecycleIntegrationTest`도 포함한다. 이 검증은 실제 어댑터로 만든
정지/실행 컨테이너를 새 JVM이 영속 기록만으로 회수하고 반복 회수와 자원 부재를 확인한다.
기존 I1의 비교 자원 보존 검증도 함께 실행한다.

## I3 protocol transport와 중앙 증거 연결

`ContainerWorkerManifest`는 서버가 관리하는 이미지 ID(`sha256:…`), jar SHA-256,
module ID와 허용 experiment 집합을 받는다. `ContainerWorkerExecutor`는 기존
`LocalJvmExecutor`의 HELLO/PHASE/METRIC/CHECK/REPORT 저장 로직을 재사용하며
`ContainerWorkerProcess`만 교체한다. HTTP 요청에서 이미지·명령을 선택하는 API나
Spring 기본 등록은 제공하지 않는다. DB 접근이 없는 fixture로 검증하는 단계다.

실행별 호스트 workspace 아래 `container-lifecycle/`에 I2 기록을 남긴다. 먼저
`sleep 900`을 PID 1로 하는 경계를 생성·시작하고 응답을 확인한 뒤, 같은 전체 ID에
TTY 없는 `docker exec -i`로 Java 21을 실행한다. 이 순서로 protocol reader 연결 여부와
I2 start 응답의 확정을 분리한다. exec 응답 유실이나 worker 자손의 분리는 컨테이너
전체 회수로 처리한다. PID 1의 900초 종료는 안전장치이며 회수 완료의 대체 증거가 아니다.

이미지는 `/opt/lab/worker.jar`, Java 21, `sh`, `env`, `sha256sum`, `mkdir`, `ln`, `sleep`, `tar`을
포함해야 하며 VOLUME 선언·추가 mount가 없어야 한다. 고정 bootstrap이 jar 실제 해시를
검사하고 `/tmp/worker`를 만든 다음 `java -Xmx128m -jar /opt/lab/worker.jar`를 실행한다.
환경의 Java 옵션 4개를 제거하고 HELLO의 Java 버전·module·experiment·배포 해시를
검증한다. 이미지의 PATH와 도구는 신뢰하는 배포 구성에 속한다. worker에 보내는 workspace는
`/tmp/worker`이며 호스트 경로는 mount하지 않는다. I2의 256 MiB 메모리, CPU 1개,
PID 64개, network=none, 16 MiB tmpfs 프로파일을 그대로 사용한다.

stdin command, stdout protocol, stderr drain은 별도 bounded 작업으로 처리한다.
`WorkerProcess.Limits`의 protocol/event/전체 바이트 상한, stderr 보존 상한과 deadline을
재사용한다. stderr는 상한 이후에도 버리면서 계속 읽어 pipe 정체를 막고 전체 바이트 수를
기록한다. 기본 timeout은 15분, stderr 보존은 1 MiB, IO 종료 대기는 30초다. lifecycle
호출도 경과 시간에 포함하지만 진행 중인 I2 CLI 호출을 전체 deadline으로 강제 취소하지는
않는다. 각 I2 호출의 30초 제한과 회수 시간은 별도로 필요하므로 엄격한 전체 wall-clock
종료 상한을 보장하지 않는다.

REPORT는 `docker exec <전체ID> tar -C /tmp/worker -cf - -- <파일명>`의 tar stream에서
정확히 하나의 regular file만 읽는다. 테스트 환경의 `docker cp`는 worker tmpfs 파일을
조회하지 못하므로 컨테이너 안에서 archive를 읽는다. shell 문자열로 파일명을 조합하지 않는다. 현재는 영숫자로 시작하는 최대 100자 basename만 지원하며 하위
디렉터리, symlink/hardlink/sparse file과 추가 entry를 거부한다. 파일당 16 MiB, 실행당
64 MiB/32개, archive 부가 바이트 64 KiB로 제한한다. 실제 worker 가용 공간은 16 MiB
전체 tmpfs 상한의 영향도 받는다. 호스트 임시 파일을 만든 뒤 기존 `WorkerArtifactImporter`가
크기·SHA-256을 다시 확인하고 저장소로 복사·등록한다. 파일 복사 실패는 실행 실패이며
검증되지 않은 artifact를 등록하지 않는다. 실패 시 이미 저장된 부분 증거는 유지한다.

RESULT만으로 완료하지 않는다. protocol EOF, exec 종료 코드 0, 증거 flush, 직접 CLI/IO
종료 및 I2의 같은 엔진·정확한 ID 부재 확인을 모두 통과한 뒤 중앙 실행 결과를 반환한다.
회수 미확인은 `CONTAINER_CLEANUP_UNCONFIRMED`로 실패하고 journal을 보존한다.
생성·시작 미확인은 I2와 같이 운영자 판단을 요구한다. 이 단계에는 그 상태를 중앙 접수
차단/자동 복구는 아래 I5의 별도 v2 정책으로 연결한다.
기존 LOCAL_JVM marker와 completion receipt는 변경하거나 해제하지 않는다.

저장소 execution kind에 `CONTAINER`를 추가했다. 측정 범위는 `WORKER_PROCESS`이며
HELLO 없는 성공 증거를 허용하지 않는다. 비교 API는 worker 환경을 사용하고 미확인 환경을
controller 환경으로 대체하지 않는다. 기존 IN_PROCESS/LOCAL_JVM 기록과 export 형태는
유지하지만 새 kind를 저장한 DB를 이전 바이너리로 읽는 downgrade 호환성은 보장하지 않는다.
I5에서 CONTAINER 등록을 명시적으로 선택한 경우에만 catalog capability가 CONTAINER를 광고한다.

검증 명령은 `./gradlew test containerWorkerTest`다. 별도 Docker suite는 캐시된
`eclipse-temurin:21-jre`의 실제 이미지 ID에 UUID 임시 태그를 붙이고 Java 21 fixture jar만 추가한 UUID
태그 이미지를 만든다. 자동 pull·네트워크 빌드·특권 실행은 하지 않는다. 정상 증거/REPORT,
stderr 초과, 자손 프로세스, 부분 실패, 잘못된 해시, malformed protocol, nonzero exit,
deadline과 중단을 검사하고, 각 실행의 removed 기록과 Docker 부재를 확인한다.
시험 이미지와 base 임시 태그는 suite 종료 후 제거하며 기존 JRE 이미지는 유지한다.

시험 이미지 정리는 삭제 CLI를 최대 20초 기다린 뒤 정확한 UUID 태그 부재를 별도 조회로
확인한다. 응답이 늦어도 실제 부재가 확인되면 완료이며, 조회 실패·태그 잔존은 실패다.
기존 이미지나 공유 build cache를 prune하지 않는다.

## I4: controller 소유 sequence 자원

`ContainerWorkerResources`는 transport가 `prepare → 실행 → finish(workerRemoved)` 순서로
호출하는 모듈 자원 계약이다. `SequenceContainerResources`를 `ContainerWorkerExecutor`에
명시적으로 주입한다. 기본 catalog와 LOCAL_JVM 등록은 그대로 유지한다. 일반 transport는
sequence 분기나 DB 설정을 알지 못한다.

controller는 고정된 MySQL image ID(`sha256:…`)로 실행별 internal bridge 네트워크와 DB를
준비한다. worker는 같은 네트워크의 `sequence-db:3306/sequence_lab`에만 연결하며 Docker
소켓·호스트 mount·공개 port가 없다. 네트워크 전체 ID, 단일 endpoint, DB alias와 소유 label을
검증한다. worker 프로파일은 `isolated-network-v1:<networkId>`이며 기존 CPU 1, 메모리
256 MiB, PID 64, 읽기 전용 root와 `/tmp` 16 MiB 제한을 유지한다.

DB 프로파일은 `sequence-mysql-v1:<networkId>:<credentialHash>`다. UID/GID 999,
CPU 1, 메모리 512 MiB, PID 128, read-only root, cap-drop ALL, no-new-privileges,
로그 비활성, restart=no를 고정한다. `/tmp` 16 MiB, `/var/lib/mysql` 256 MiB,
`/var/run/mysqld` 1 MiB tmpfs만 쓰며 모두 noexec/nosuid/nodev다. 데이터는 실행 종료 시
폐기한다. readiness는 controller의 bounded Docker exec `SELECT 1`로 확인하며 command
기한과 120초 중 짧은 제한을 사용한다. 개별 Docker CLI 호출에는 기존 30초 제한이 적용된다.

controller가 생성한 일회성 DB 비밀번호는 `LAB_SEQUENCE_DB_MODE=EXTERNAL`,
`LAB_SEQUENCE_DB_HOST=sequence-db`, `LAB_SEQUENCE_DB_PASSWORD` 환경으로 전달한다.
비밀번호는 64자리 소문자 hex이며 worker는 mode·host·형식을 검증한다. JDBC 주소와
사용자 lab은 고정하고 사용자 시나리오에는 variant/workers/blockSize만 허용한다.
root 비밀번호는 별도로 생성하며 worker에 전달하지 않는다. journal에는 평문 비밀번호를
저장하지 않는다. Docker 관리자는 컨테이너 환경을 inspect할 수 있다.

정리 순서는 **worker 경계 제거 확인 → DB 제거 확인 → 빈 네트워크 제거 확인**이다.
준비 실패와 인증 실패도 같은 순서로 회수한다. worker 또는 DB 종료가 미확인인 경우
뒤의 자원은 보존하고 성공 결과를 반환하지 않는다. 자원 정리 실패는 workspace를 포함한
`RESOURCE_CLEANUP_UNCONFIRMED`로 전달한다.

host workspace의 `sequence-resources/`에는 각 4 KiB 이하인 append-only
`intent.json`, `created.json`, `removing.json`, `removed.json`을 남긴다. 생성 요청 전 intent를
force하고, 확인된 network ID와 intent hash를 created에 기록한다. DB는 하위 `mysql/`에서
I2의 배타 잠금·6단계 기록을 재사용한다. network 생성 응답이 불확실하면 생성 재시도나
이름 기반 삭제를 하지 않는다. 이 단계는 새 세션의 수명주기만 제공한다. controller 재시작 시
네트워크 기록 재개, admission marker 해제 및 장애 주입은 아래 I5 계약으로 연결했다.
기존 LOCAL_JVM 완료 기록과 `--recover`의 의미는 바뀌지 않는다.

검증 순서(두 Gradle wrapper는 병렬 실행하지 않는다):

```bash
./scripts/career-lab.sh test :mcp-platform:sequenceWorkerManifest
./gradlew test sequenceContainerTest containerWorkerTest workerContainerIsolationTest careerSequenceTest
```

`sequenceContainerTest`는 캐시된 `eclipse-temurin:21-jre`와 `mysql:8.0.39`를 사용한다.
실제 worker jar의 BEFORE/ATOMIC_UPDATE/SELECT_FOR_UPDATE 메트릭·CHECK·REPORT를
중앙 저장하고 인증 실패 시 부분 증거와 자원 회수를 검증한다. 시험 이미지 태그와 실행별
자원만 정리한다. 실패 workspace는 조사용으로 보존한다.

## I5: 같은 저장소에서 시작 시 복구와 접수 재개

`WorkerRegistration`은 LOCAL_JVM과 CONTAINER의 routing/capability 계약만 공통화한다.
기존 v1 marker·completion receipt·LOCAL_JVM 복구 정책은 그대로다. CONTAINER 등록은
`ContainerWorkerRegistration`이 담당하며 RunStore의 배타 소유권과 중단 Run 복원이 끝난
뒤에만 `recover`를 호출한다. 새 Run을 자동 재실행하거나 중단 Run의 증거를 완료로 바꾸지 않는다.

`ContainerRecoveryGuard`는 등록별 잠금 `.container-<id>.lock`을 실행/복구 동안 유지한다.
Docker 요청 전에 `.container-active-<id>.json`을 CREATE_NEW/force로 기록한다. v2 기록은
등록 ID, Run ID, experiment ID, worker image ID·artifact hash·module ID, resource profile,
UUID workspace 이름에 결합되며 4 KiB 이하다. workspace 생성 전에 기록하므로 그 사이의
컨트롤러 사망도 추적할 수 있다. workspace와 모든 자원 기록은 별도 보관하고 marker만
완료 후 삭제한다. 같은 ID의 v1 또는 v2 기록이 남으면 실행 방식 변경으로도 우회하지 못한다.

정상 실행은 protocol EOF·exit·증거 flush와 worker/DB/network 정리까지 반환된 경우 marker를
해제한다. 실패/중단으로 transport가 반환하지 못하면 기록을 남긴다. 시작 시 복구는 다음을 따른다.

1. 등록 잠금을 획득하고 v2 기록의 크기·형식·배포·경로를 검증한다. symlink와 경로 이탈은 거절한다.
2. 같은 RunStore의 terminal Run, experiment, CONTAINER execution/module을 확인한다.
3. sequence 자원 기록을 읽어 전용 network ID를 복원한다. worker journal의 I2 소유권과
   격리 설정을 재검증하고 확인된 컨테이너 경계를 제거한다.
4. DB journal을 재검증해 DB를 제거한다. DB credential hash는 기존 profile에서 복원하고
   실제 inspect 환경의 일회성 credentials와 대조한다. 평문 비밀번호를 host journal에 추가하지 않는다.
5. 동일 엔진·network ID·name·label·internal bridge·빈 endpoint를 확인해 네트워크를 제거한다.
   이미 제거된 경우에도 동일 엔진의 성공한 정확한 ID 조회와 기록 결합을 검증한다.
6. 마지막까지 marker 바이트가 같고 interruption이 없을 때만 해당 v2 marker를 삭제한다.
   원래 Run 상태·실패 이유·worker 정보·부분 CHECK/metric/artifact는 바꾸지 않는다.

worker/DB의 create 또는 start 응답이 미확인이면 I2가 `REQUIRES_OPERATOR`를 반환하며
후속 자원 회수와 접수 해제를 하지 않는다. network create 응답이 미확인인 경우도 마찬가지다.
삭제 응답 유실은 `removing` 기록과 다음 시작의 확인된 부재로 재개할 수 있다. 엔진 변경,
Docker 조회 실패, 다른 소유자/배포/endpoint, 손상/부분/초과 기록은 차단을 유지한다.
복구는 시작마다 한 번 수행하고 background retry나 prune을 하지 않는다. 미확인 요청의
지연 완료를 배제할 수 없다면 기록을 수동으로 지우지 말고 기존 운영자 조정 절차를 따른다.

선택 설정은 [배포 가이드](career-worker-deployment.md#container-실행과-시작-시-복구-i5)에 있다.
`recover-on-startup=false`가 기본이며 이를 켜도 v1 완료 기록 없는 LOCAL_JVM 실행은 자동 해제하지 않는다.
개별 Docker CLI의 출력 64 KiB/30초 상한과 I2 기록 한도를 유지한다. 전체 복구에는 여러 CLI
호출이 순차로 필요하다. 디렉터리 fsync를 포함한 전원 장애 내구성과 공유 daemon 자체 재시작은
이 I5 회귀 suite의 보장 범위가 아니다.

검증은 `ContainerStartupRecoveryTest`, `SequenceResourceRecoveryTest`의 create/start/delete
응답 유실·Docker 불가·엔진/소유권 변경·손상 기록 주입과 `sequenceContainerTest`의 실제
컨트롤러 JVM 강제 종료→같은 저장소 opt-out 차단→opt-in 자원 회수→다음 Run 성공을 포함한다.
실제 테스트는 HELLO를 저장한 뒤 컨트롤러를 SIGKILL하고 새 앱 인스턴스로 복원하며,
소유 중인 RunStore를 다른 앱이 열면 복구 전에 거절하는지도 확인한다. 모든 시험 자원은
소유 기록을 통해서만 정리한다.

### 시험 이미지의 로컬 조립

`ContainerFixtureImage`는 캐시된 Java 21 base의 전체 이미지 ID를 조회하고
`create --pull=never`로 실행하지 않을 컨테이너를 만든다. `/opt/lab/worker.jar`를 복사하고
`commit`으로 시험 이미지를 저장한 뒤 생성용 컨테이너의 전체 ID·base·소유 토큰·created
상태를 대조해 제거한다. 시험 이미지는 각 suite 종료 시 제거한다. 생성용 컨테이너는
네트워크가 없으며 한 번도 시작하지 않는다.

기존 임시 `FROM engineering-lab-worker-test:<UUID>-base` 방식은 `--pull=false`여도
BuildKit이 해당 이름의 Docker Hub blob을 조회했다. 2026-10-14 실패 시각의 daemon 로그에
원격 HEAD 요청이 60초 후 취소된 기록이 있어 이 경로를 제거했다. 새 조립은 registry와
BuildKit을 사용하지 않으며 CLI 제한 시간과 실제 worker 실행·격리 검증은 유지한다.
공통 suite의 실패 build/workspace는 보존하고 timeout 메시지에 로그 경로와 마지막 8 KiB를
남긴다. daemon 자체의 조회·삭제 응답 지연은 별개의 실패로 계속 보고한다.
