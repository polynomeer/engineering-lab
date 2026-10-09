# Worker wire protocol v1

D4 첫 단위의 실행 가능한 계약이다. [설계](../../../docs/dashboard-integration-design.md)의 초안을 아래 범위로 고정한다. Java 21 구현은 [worker-support](../../../career-lab/worker-support/README.md)에 있다. 현재 루트 앱에는 외부 프로세스 실행기가 등록되지 않았으며 `/dashboard/v1/capabilities`의 `localJvm=false`, 빈 `workerProtocolVersions`는 유지된다.

## 데이터 형식

- stdin 명령: [command.schema.json](command.schema.json), UTF-8 JSON 한 줄. 이후 부모 연결 감지를 위해 stdin을 열어둔다. `ParentConnection`과 `WorkerProcess`가 EOF 감시 callback·deadline 대기를 제공한다. 실제 Career harness의 자원 정리는 후속 단계다.
- stdout 이벤트: [event.schema.json](event.schema.json), UTF-8 NDJSON. 한 프로세스가 한 Run만 수행한다. 일반 로그는 stderr로 보낸다.
- 각 레코드는 LF로 끝나야 한다. 마지막 LF 누락, 빈 줄, 잘못된 UTF-8, 중복 key, 여러 JSON 값, 알 수 없는 필드·버전·이벤트는 오류다. 정수 필드는 `1.0`이나 문자열을 강제 변환하지 않는다.
- 문자열 최대 4096자, key 1–100자, 객체/배열당 최대 64항목, 중첩 16단계, 숫자 표기 128자다. 필드별 더 작은 제한은 schema를 따른다. 무한대/NaN은 허용하지 않는다. Java 구현의 문자 길이는 UTF-16 code unit 기준으로 보수적으로 제한한다.
- parameters와 summary는 최대 64개의 문자열·boolean·유한 숫자로 구성한다. null과 중첩 값은 받지 않는다. 실제 파라미터와 metric key/unit이 등록 catalog와 일치하는지는 실행 어댑터가 추가 검증한다.

JSON Schema는 개별 레코드 구조를 기술한다. 바이트 상한, 정수 토큰 표현, 중복 key, 유한 숫자, 이벤트 간 순서·개수·identity는 codec이 추가 검증한다. Schema 통과만으로 정상 Run을 판정하지 않는다.

## 이벤트 순서와 완료

1. HELLO는 sequence=0으로 시작한다. runId, moduleId, experimentId, definitionSha256, artifactSha256이 부모가 제공한 `Identity`와 정확히 같아야 한다. JVM/OS와 nullable Git 정보는 실제 worker가 제공하며 fixture 값을 운영 환경에 사용하지 않는다.
2. sequence는 빠짐없이 1씩 증가한다. 중복과 건너뜀을 모두 거부한다.
3. PHASE는 PREPARING → MEASURING → FINALIZING 순서다. 실패 정리를 위해 PREPARING → FINALIZING을 허용한다. 중복·역행은 거부한다. METRIC/CHECK는 MEASURING 또는 FINALIZING에서, ARTIFACT는 FINALIZING에서만 허용한다.
4. RESULT는 정확히 한 번, 마지막 이벤트다. lastSequence는 RESULT 자신의 sequence다. counts는 RESULT 이전의 HELLO/PHASE/METRIC/CHECK/ARTIFACT 개수이며 관측과 정확히 같아야 한다.
5. SUCCEEDED 결과에는 MEASURING과 FINALIZING이 필요하고 FAIL check가 없어야 한다. 준비 실패는 HELLO 직후 FAILED/INTERRUPTED 결과를 보낼 수 있다. RESULT의 실패 상태를 exit 0만으로 성공으로 바꾸지 않는다.
6. RESULT 뒤 추가 레코드, RESULT 없는 EOF, 잘린 프레임은 실패다. `Reader.verifyExit(0)`은 정상 EOF와 RESULT를 확인한 뒤에만 통과한다. 이는 **통신 검증**이며 Run의 성공 확정이 아니다. 부모는 RESULT 상태, 실제 종료 코드, 모든 증거 flush와 artifact 검증까지 확인해야 한다.

CHECK sequence는 전체 이벤트 순번이다. 향후 저장 어댑터가 `RunCheck.sequence`에 별도의 0부터 증가하는 check cursor를 부여해야 하며 둘을 혼용하지 않는다.

## 용량과 배압

| 제한 | 기본값 / v1 최대값 |
| --- | ---: |
| 레코드 | LF 제외 65,536 bytes |
| stdout 전체 | LF 포함 67,108,864 bytes |
| 이벤트 | HELLO/RESULT 포함 100,000개 |
| 객체/배열 | 각 64항목 |

`ProtocolLimits(recordBytes, totalBytes, events)`로 세 한도를 낮출 수 있으며 최대값을 높일 수는 없다. 명령은 레코드와 totalBytes 한도를 적용한다. `WorkerProcess.Limits`로 프로세스별 제한을 전달한다. 루트 서버 설정과의 연결은 실행 어댑터 단위에 남아 있다.

Reader는 한 레코드만 보유하고 추가 이벤트를 모으지 않는다. Writer는 직렬화 중에도 64 KiB를 넘으면 중단하며 성공 시 매 프레임을 flush한다. 호출자는 읽은 이벤트를 bounded 저장 경로에 동기 전달한 뒤 다음 프레임을 읽어 pipe까지 배압이 전달되게 해야 한다. 이 라이브러리는 비동기 큐를 추가하지 않는다. 읽기/쓰기 오류 뒤 같은 세션의 계속 진행이나 자동 재시도는 금지한다. 스트림 소유권과 종료는 호출자에게 있다.

ARTIFACT 경로는 slash로 구분한 상대 경로다. 절대 경로, `.`/`..`, 빈 요소, backslash, colon, NUL을 거부한다. 이는 문자열 검증이다. workspace 소유권·실제 파일 containment·symlink·크기/hash와 종류 허용 여부는 다음 실행 어댑터가 검증해야 한다. 현재 codec은 파일을 열지 않는다. kind는 JFR/LOG/REPORT다.

## 공유 예제와 검증

`fixtures/command.json`, `success.ndjson`, `failure.ndjson`은 합성 예제이며 실행 성능이나 실제 배포물 hash의 근거가 아니다. `fixtures/errors.json`은 오류 예제 파일과 예상 reason code를 연결한다. 원문 payload·workspace를 오류 메시지로 되돌려주지 않는다.

```bash
./scripts/career-lab.sh :worker-support:test
./scripts/career-lab.sh test
./gradlew test
```

`WorkerProtocolTest`는 정상/실패 결과, 누락·역순·개수 불일치, 경계값, 무한 입력의 조기 중단, UTF-8·JSON 오류, 출력 중단 후 재시도 금지, 경로 탈출을 검증한다. `WorkerSchemaTest`는 독립 JSON Schema 검증기로 정상 예제와 구조 오류를 확인한다. `WorkerProcessTest`는 실제 테스트용 JVM의 stderr drain/포화, 부모 EOF 감시, deadline/강제 종료, 저장 callback의 지연/flush 오류를 검증한다. 루트 `LocalJvmExecutorTest`는 실제 Java 21 worker와 H2 RunStore의 저장·재개방·저장 오류를 추가 검증한다. 실제 Career 배포·artifact/컨테이너 검증은 후속 단계다.
