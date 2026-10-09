# Worker support

Java 21용 통신 라이브러리다. `com.pnomeer.lab.worker` 패키지에서 [worker v1 계약](../../contracts/worker/v1/README.md)을 구현하며 Spring Boot나 루트 Java 25 core에 의존하지 않는다. Jackson 2를 사용하고 Career 도메인 모듈의 소스·Boot 버전은 변경하지 않는다.

`lab-module.properties`의 collection은 `infrastructure`다. 기존 자동 발견 규칙으로 빌드에 포함되며 실험 catalog에 등록되는 여섯 번째 Career 실험이 아니다. 향후 harness의 별도 `labRuntime` source set에서 `project(":worker-support")`를 의존한다. 루트 `LocalJvmExecutor`는 공유 wire schema와 fixture를 기준으로 중앙 저장소에 연결하며, 실제 Career catalog 등록은 후속 단계다.

## API

- `WireJson.object()` / `parse(byte[])` / `encode(ObjectNode)`: 제한된 JSON 처리. 외부 raw input은 먼저 `WorkerProtocol`의 크기 제한 reader를 통과시킨다.
- `WorkerProtocol.readCommand(input, limits)` / `writeCommand(output, command, limits)`: LF로 끝나는 명령 한 줄. readCommand는 다음 제어 바이트를 미리 읽지 않으며 스트림을 닫지 않는다.
- `WorkerProtocol.Identity`: 서버 manifest와 검증된 명령에서 얻은 run/module/experiment/definition hash/artifact hash. worker가 보내온 HELLO로 예상 identity를 만들지 않는다.
- `WorkerProtocol.Reader.next()`: 검증된 이벤트 한 개. null은 RESULT 이후 정상 EOF다. 호출자가 실제 종료 코드를 얻은 후 `verifyExit(exitCode)`를 호출한다. 결과 상태와 DB 저장 확인은 별도 책임이다.
- `WorkerProtocol.Writer.emit(type, payload)`: HELLO·PHASE·METRIC·CHECK·ARTIFACT를 동기 출력한다. `result(status, reason, summary)`는 sequence/counts를 자동 생성해 마지막 결과를 출력한다.
- `ProtocolLimits.DEFAULT`: 레코드 64 KiB / 전체 64 MiB / 100,000개. 인스턴스로 더 작은 상한을 주입할 수 있다.

Reader/Writer는 하나의 소유 스레드에서 사용한다. 여러 측정 스레드의 이벤트를 보내려면 harness가 bounded 전달 경로를 소유해야 한다. 라이브러리는 프로세스를 띄우거나 일반 로그를 stdout으로 보내지 않는다. 실제 worker JVM 정보 수집과 파일 검증·컨테이너 정리는 아직 구현 전이다. 부모 연결 감시와 timeout 기반 프로세스 transport는 아래 단위에서 제공한다.

## 검증

저장소 루트에서 `./scripts/career-lab.sh :worker-support:test`를 실행한다. 이 모듈의 테스트 자체는 Docker 없이 동작한다. Career 전체 회귀는 Docker를 실행한 상태로 `./scripts/career-lab.sh test`, 루트 회귀는 `./gradlew test`다. schema/fixture 변경도 이 테스트 입력으로 추적한다.

## 별도 JVM 실행 기반 (D4 두 번째 단위)

`WorkerManifest`는 서버가 지정한 Java 실행 파일, 자체 실행 가능한 jar, SHA-256, 모듈·허용 실험 목록, heap 상한을 보관한다. 사용자 파라미터에서 생성하면 안 된다. `preflight`는 실험 허용 여부·Java 경로·jar(최대 256 MiB)·hash를 검사한다. 실제 Docker 준비 검사는 아직 연결 전이다.

`WorkerProcess.execute(manifest, command, workspaceRoot, sink)`는 관리 디렉터리에 Run별 임시 workspace를 만든다. jar를 제한된 크기로 복사해 hash를 다시 검사하고 `java -Xmx… -jar …` 인자 배열로 실행한다. shell을 사용하지 않으며 ambient Java 옵션 환경변수는 제거한다. 다른 환경변수는 상속하므로 관리자가 worker에 제공할 실행 환경을 관리해야 한다. 생성한 workspace는 성공/실패 모두 보존한다. 현재 자동 삭제·총 디스크 quota는 없으므로 운영 catalog에 등록하기 전 후속 저장/정리 절차가 필요하다.

`Limits.DEFAULT`는 전체 실행 대기 15분, 종료 유예 30초, stderr 보관 1 MiB다. 세 값과 protocol 상한은 인스턴스로 낮출 수 있다. 명령의 deadline과 설정 중 먼저 오는 시한을 사용하며 monotonic clock으로 stdout/EOF/종료/flush 대기를 제한한다. jar 사전 파일 검사는 동기 I/O이므로 강제 중단 가능한 작업으로 보장하지 않는다. graceful 종료·강제 종료·I/O 스레드 정리에는 각각 종료 유예를 적용한다.

세 개의 전용 daemon 스레드가 명령 전송, stdout 검증·저장, stderr drain을 담당한다. stderr는 보관량을 넘겨도 계속 읽어 pipe 포화를 방지한다. 정상 반환의 `Result`에는 RESULT payload, 보관한 stderr, 전체 stderr 바이트 수, workspace가 있다. RESULT의 FAILED/INTERRUPTED는 그대로 반환한다. 오류는 `Failure`의 reason code와 workspace로 확인한다. 오류 시 stderr 보관본 반환은 아직 지원하지 않는다.

`EvidenceSink.persist`는 검증된 이벤트를 동기 저장하고 ARTIFACT 실제 파일·hash·허용 종류도 검증해야 한다. `flush`는 저장 완료를 확인해야 한다. 두 callback은 bounded·interruptible이어야 하며 timeout 이후 계속 쓰면 안 된다. flush 오류나 미종료 I/O 스레드는 성공으로 반환하지 않는다. 루트의 `LocalJvmExecutor`가 RunStore worker/check sink와 bounded metric writer에 연결한다. 실제 Java 21 fixture와 H2 재개방·저장 오류 주입으로 검증했다. 운영 catalog 등록과 ARTIFACT 실제 파일 검증은 아직 후속 단계다.

`ParentConnection.watch`는 명령을 읽은 뒤 worker가 stdin EOF/추가 바이트/읽기 오류를 감지하도록 돕는다. callback에서 실제 harness와 자원을 종료해야 한다. 통합 테스트 worker는 즉시 halt하지만 실제 Career harness에는 컨테이너 정리와 제한 시간 종료를 구현해야 한다. 부모는 자신이 시작한 직접 자식 Process만 종료하며 자손 프로세스·컨테이너 정리는 아직 제공하지 않는다.

`processFixtureJar`는 테스트 전용 자체 실행 jar다. 배포 worker나 실험 결과물이 아니다. `WorkerProcessTest`는 실제 Java 21 JVM에서 stderr 2 MiB 출력, RESULT 누락·잘못된 JSON·64 KiB 초과, nonzero exit, timeout·종료에 응답하지 않는 자식의 강제 종료, 부모 연결 종료, 저장 지연·flush 실패와 자식 종료를 검증한다.

루트 Gradle은 이 디렉터리를 독립 composite build로 참조한다. `settings.gradle.kts`는 그 진입점이며 Career 전체 settings의 자동 발견도 유지한다. 동일 산출물 경로를 공유하므로 루트와 Career 빌드를 병렬 실행하지 않는다. 루트 `./gradlew test`는 Java 25와 21 설치가 필요하고 테스트 worker jar를 자동 생성한다.
