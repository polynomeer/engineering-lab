# Registry와 선언적 Scenario

실험 ID는 `concurrency.queue-contention`, `io.endpoint-comparison`, `pipeline.excel-ingestion`이다. `ExperimentRegistry`에 실행 코드와 schema를 명시적으로 등록한다. 중복 ID는 시작 시 실패한다. 문서에서 클래스 이름이나 명령을 지정해 실행할 수 없다.

## 정의 v1

```yaml
schemaVersion: 1
experiment: concurrency.queue-contention
scenario:
  id: small-buffer
  hypothesis: A slow consumer causes producer waiting after the queue fills.
parameters:
  queueCapacity: 2
  producerThreads: 2
  consumerThreads: 1
  itemsPerProducer: 100
  consumerDelayMs: 2
measurement:
  mode: SINGLE_PASS
```

동일 구조의 JSON도 지원한다. 모든 필드는 명시적으로 검증하고 parameters는 전부 필수다. 알 수 없는 필드, 중복 키, 잘못된 타입, 다중 문서, 미지원 version/experiment/measurement는 실행 전에 거부한다. `"2"`를 숫자 2로, `2.5`를 정수 2로 변환하지 않는다. YAML은 safe constructor를 사용하며 사용자 정의 객체 tag와 collection alias를 허용하지 않는다.

`SINGLE_PASS`는 지정된 workload를 한 번 실행한다. JVM warmup, 반복 측정, duration 기반 종료를 뜻하지 않는다. `warmup`, `duration`, `repetitions`를 추가해도 무시하지 않고 거부한다. 가설은 관측 결과가 아닌 실행자의 검증 대상이다.

Canonical JSON은 고정된 v1 구조와 정렬된 key, UTF-8, 정수 값을 사용한다. 주석·공백·map 순서를 제외한 의미가 같으면 정의 SHA-256도 같다. 실행 metadata parameters에는 `scenario.definition`, `scenario.definition.sha256`, `scenario.hypothesis`, `measurement.mode`를 저장한다. 원본 JSON/YAML은 Git으로 관리한다.

## 파라미터

- Queue: `queueCapacity` 1–100000, `producerThreads`/`consumerThreads` 1–64, `itemsPerProducer` 1–1000000, `consumerDelayMs` 0–60000.
- IO: `requests` 1–10000, `delayMs`/`pinDelayMs` 0–60000, `pinning` boolean. pinning이라는 옵션 이름만으로 특정 JDK의 pinning 발생을 결론내리지 않는다.
- Excel: `mode` PIPELINE 또는 SINGLE_THREAD, `rawCapacity`/`mappedCapacity` 1–100000, `validatorThreads`/`inserterThreads` 1–64, `chunkSize` 1–10000, `offerTimeoutMs` 1–60000.

단위와 타입은 `ExperimentDescriptors`의 명시적 schema로 관리한다. 범위를 변경하면 이 문서와 테스트도 갱신한다. Excel 정의의 설정은 실행별 PipelineRunner에 적용하고 기존 singleton 설정을 변경하지 않는다.

Excel 정의에는 다음 input도 필수다.

```yaml
input:
  file: fixtures/sample.xlsx
  sha256: <64자리 소문자 SHA-256>
```

file은 시나리오 디렉터리 안의 상대 경로이며 상위 경로, 절대 경로, URL을 허용하지 않는다. 서버에서는 이 경로를 열지 않고 전달된 XLSX bytes와 checksum을 검증한다. 다른 실험에는 input이나 업로드를 허용하지 않는다.

## 제한 설정

`lab.scenario.max-document-bytes` 기본값은 65536, `max-input-bytes`는 52428800이다. 설정 가능한 상한은 각각 1 MiB와 50 MiB다. 기존 Spring multipart 요청 한도(50 MB)도 적용된다. 문서 parser는 stream에서 limit+1 byte까지만 읽어 초과를 감지한다. 입력 checksum 검증은 실행 생성 전에 수행한다.

## HTTP API

- `GET /experiments`: 세 실험의 ID/제목/분류, 입력 종류, 파라미터 schema, 측정 모드.
- `GET /experiments/{experimentId}`: 한 실험의 schema. 없는 실험은 404.
- `POST /runs`: application/json 또는 application/yaml 정의를 body로 전달. Excel은 multipart/form-data의 `scenario` 문서 part와 `file` XLSX part로 전달한다.
- 성공 접수 시 202, Location `/runs/{runId}`, `{runId, experimentType, scenarioId, definitionSha256}`를 반환한다. 이후 기존 `GET /runs/{runId}`로 완료/실패를 확인한다.

정의·파라미터·입력 hash 검증이 끝나기 전에는 Run을 생성하지 않는다. 잘못된 정의는 400이다. executor 제출 거부는 실패 Run을 남기고 503을 반환한다. Excel의 시나리오 ID는 실행 모드와 독립적이다. 기존 ingestion 조회는 metadata의 mode를 사용해 복원한다.

기존 실험별 POST endpoint는 호환 경로로 유지되며 선언적 정의 hash를 새로 만들어 붙이지 않는다.

## 파일 CLI

저장소 루트에서 실행한다. `validate`는 서버 없이 정의/schema와 필요한 input hash를 검사한다.

```bash
./gradlew -q lab --args='validate experiments/concurrency/queue-contention/scenarios/small-buffer.yaml'
./gradlew -q lab --args='validate experiments/pipeline/excel-ingestion/scenarios/small-batch.yaml'
```

서버를 `./gradlew bootRun`으로 시작한 뒤 다른 터미널에서 실행한다. CLI는 HTTP 클라이언트이며 Spring이나 evidence DB를 직접 열지 않는다.

```bash
./gradlew -q lab --args='list'
./gradlew -q lab --args='run experiments/concurrency/queue-contention/scenarios/small-buffer.yaml --wait'
./gradlew -q lab --args='run experiments/io/endpoint-comparison/scenarios/baseline.json --wait'
./gradlew -q lab --args='run experiments/pipeline/excel-ingestion/scenarios/small-batch.yaml --wait'
```

기본 서버는 `http://localhost:8080`이다. `--server http://localhost:8090`처럼 HTTP(S) origin으로 바꿀 수 있다. `--wait`가 없으면 접수 응답을 출력하고 끝낸다. 있으면 기본 120초까지 완료를 조회하며 `--timeout-seconds 30`으로 1–3600초 범위에서 바꾼다. 대기 timeout은 서버 실행을 취소하지 않는다. POST를 자동 재시도하지 않으며, 같은 정의를 다시 제출하면 새 Run이 만들어진다.

stdout은 JSON lines다. validate는 검증 결과/hash, list는 catalog 배열을 출력한다. run은 접수 응답을 한 줄 출력하고 --wait 사용 시 최종 결과를 한 줄 더 출력한다. Java CLI의 종료 코드는 성공 0, 검증/HTTP/실험 실패 1, 완료 대기 timeout 2다. Gradle JavaExec로 호출하면 비정상 종료를 Gradle task 실패로 보고한다.

CLI 문서/파일 한도는 각각 64 KiB/50 MiB이고 서버가 더 작은 한도를 설정하면 서버가 거부한다. XLSX는 8 KiB 버퍼로 hash를 검증한 뒤 multipart로 스트리밍 전송한다. input symlink가 시나리오 디렉터리 밖을 가리키면 거부한다. 서버 응답은 1 MiB로 제한하고, connect timeout은 5초, HTTP 요청 전체의 대기는 최대 30초 또는 남은 --wait 시간 중 짧은 값이다. HTTP redirect는 따라가지 않는다.

## Git 관리 예제

- `experiments/concurrency/queue-contention/scenarios/small-buffer.yaml`
- `experiments/io/endpoint-comparison/scenarios/baseline.json`
- `experiments/pipeline/excel-ingestion/scenarios/small-batch.yaml`와 `fixtures/sample.xlsx`

XLSX fixture는 기존 TestWorkbookGenerator로 만든 header + 5개의 합성 유효 행이다. fixture를 변경하면 정의의 input.sha256도 갱신해야 한다. 시간 정보가 있는 XLSX를 다시 생성하면 같은 행이어도 bytes/hash가 달라질 수 있다.

시나리오 파일은 입력·파라미터·가설을 재실행할 수 있게 한다. 측정 결과가 매번 같음을 보장하지 않는다. 공유 ingestion DB의 기존 행을 자동 삭제하거나 외부 환경을 초기화하지 않으며, 시간 기반 반복/warmup·격리된 데이터 환경은 별도 기능이다. 기존 UI에는 실험별 시작 폼을 유지하며 registry 중심 UI 이행은 다음 단계다.

## 새 실험 추가

`ExperimentDescriptors`에 설명/schema를, `RegistryConfiguration`에 명시적 typed factory를 추가한다. `ExperimentRegistry`는 중복 ID를 거부한다. 공통 POST/CLI 전송 코드에는 분기를 추가하지 않는다. CLI의 offline validator도 같은 descriptor를 사용한다. 새 input 종류나 measurement mode는 실행 의미·검증·API를 함께 구현한 후 허용한다.

검증은 `./gradlew test`로 수행한다. parser/schema/hash 테스트, 잘못된 정의의 Run 미생성, Excel별 설정과 hash, 실제 HTTP 서버를 통한 세 예제 CLI 실행, timeout/실패/응답 제한을 포함한다.
