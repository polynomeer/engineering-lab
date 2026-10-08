# Worker support

Java 21용 통신 라이브러리다. `com.pnomeer.lab.worker` 패키지에서 [worker v1 계약](../../contracts/worker/v1/README.md)을 구현하며 Spring Boot나 루트 Java 25 core에 의존하지 않는다. Jackson 2를 사용하고 Career 도메인 모듈의 소스·Boot 버전은 변경하지 않는다.

`lab-module.properties`의 collection은 `infrastructure`다. 기존 자동 발견 규칙으로 빌드에 포함되며 실험 catalog에 등록되는 여섯 번째 Career 실험이 아니다. 향후 harness의 별도 `labRuntime` source set에서 `project(":worker-support")`를 의존한다. 루트 컨트롤러는 공유 wire schema와 fixture를 기준으로 어댑터를 연결해야 한다.

## API

- `WireJson.object()` / `parse(byte[])` / `encode(ObjectNode)`: 제한된 JSON 처리. 외부 raw input은 먼저 `WorkerProtocol`의 크기 제한 reader를 통과시킨다.
- `WorkerProtocol.readCommand(input, limits)` / `writeCommand(output, command, limits)`: LF로 끝나는 명령 한 줄. readCommand는 다음 제어 바이트를 미리 읽지 않으며 스트림을 닫지 않는다.
- `WorkerProtocol.Identity`: 서버 manifest와 검증된 명령에서 얻은 run/module/experiment/definition hash/artifact hash. worker가 보내온 HELLO로 예상 identity를 만들지 않는다.
- `WorkerProtocol.Reader.next()`: 검증된 이벤트 한 개. null은 RESULT 이후 정상 EOF다. 호출자가 실제 종료 코드를 얻은 후 `verifyExit(exitCode)`를 호출한다. 결과 상태와 DB 저장 확인은 별도 책임이다.
- `WorkerProtocol.Writer.emit(type, payload)`: HELLO·PHASE·METRIC·CHECK·ARTIFACT를 동기 출력한다. `result(status, reason, summary)`는 sequence/counts를 자동 생성해 마지막 결과를 출력한다.
- `ProtocolLimits.DEFAULT`: 레코드 64 KiB / 전체 64 MiB / 100,000개. 인스턴스로 더 작은 상한을 주입할 수 있다.

Reader/Writer는 하나의 소유 스레드에서 사용한다. 여러 측정 스레드의 이벤트를 보내려면 harness가 bounded 전달 경로를 소유해야 한다. 라이브러리는 프로세스를 띄우거나 일반 로그를 stdout으로 보내지 않는다. 실제 worker JVM 정보 수집, 부모 연결 감시, timeout, 파일 검증, 컨테이너 정리는 아직 구현 전이다.

## 검증

저장소 루트에서 `./scripts/career-lab.sh :worker-support:test`를 실행한다. 이 모듈의 테스트 자체는 Docker 없이 동작한다. Career 전체 회귀는 Docker를 실행한 상태로 `./scripts/career-lab.sh test`, 루트 회귀는 `./gradlew test`다. schema/fixture 변경도 이 테스트 입력으로 추적한다.
