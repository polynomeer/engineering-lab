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

이 단계에서 parser/schema/registry를 구현했다. HTTP 조회·공통 실행과 파일 CLI 연결은 다음 작업 단위에서 완료한다. 기존 실험별 API와 기존 Run 조회는 유지된다.
