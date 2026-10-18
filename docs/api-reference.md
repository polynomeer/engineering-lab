# HTTP API 참조

[문서 안내](README.md) · [화면 사용법](user-guide.md) · [정의 schema](scenarios.md)

기본 origin은 `http://localhost:8080`입니다. 인증은 구현되어 있지 않으며 로컬 사용을 전제로 합니다. 예제의 `RUN_ID`, `BASELINE_ID`, `CANDIDATE_ID`, `ARTIFACT_ID`는 실제 응답값으로 바꿉니다. 접수 성공(202)은 실험 성공을 뜻하지 않습니다.

## 1. Catalog와 선언적 실행

| Method | 경로 | 용도 |
| --- | --- | --- |
| GET | `/dashboard/v1/measurement-catalog` | 현재 공통 검증 성질·측정 계약 ([P4 계약](measurement-contract.md)) |
| GET | `/dashboard/v1/comparison-assessment?baseline=…&candidate=…` | Run에 저장된 계약·단위·환경·구간의 비교 조건 판정 |
| GET | `/dashboard/v1/study-catalog` | 전략·사례·기존 시나리오 프리셋과 패턴 의미 버전 ([계약](study-catalog.md)) |
| GET | `/dashboard/v1/pattern-catalog` | 현재 활성 실험의 분야·패턴 분류와 출처 (P1 신규) |
| GET | `/modules` | 등록된 모듈의 id/title/collection과 소유 실험 descriptor 목록 |
| GET | `/experiments` | 실험 ID·입력 종류·파라미터 schema |
| GET | `/experiments/{experimentId}` | 특정 실험 schema |
| GET | `/metrics/schema` | 단위·범위·집계 의미 catalog |
| POST | `/runs` | JSON/YAML 또는 multipart 시나리오 접수 |

```bash
curl --fail --silent --show-error http://localhost:8080/experiments
curl --fail --silent --show-error \
  -H 'Content-Type: application/yaml' \
  --data-binary @experiments/concurrency/queue-contention/scenarios/small-buffer.yaml \
  http://localhost:8080/runs
```

Excel은 `scenario`와 `file` 두 part를 보냅니다. curl이 multipart boundary를 생성하도록 전체 Content-Type을 수동 지정하지 않습니다.

```bash
curl --fail --silent --show-error \
  -F 'scenario=@experiments/pipeline/excel-ingestion/scenarios/small-batch.yaml;type=application/yaml' \
  -F 'file=@experiments/pipeline/excel-ingestion/scenarios/fixtures/sample.xlsx' \
  http://localhost:8080/runs
```

202 응답은 `{runId, experimentType, scenarioId, definitionSha256}`, Location은 `/runs/{runId}`입니다. 정의/hash 검증 실패는 Run 생성 전에 거부합니다. 같은 정의를 다시 제출하면 새 Run을 만듭니다. 정의 크기 기본 64 KiB, 입력 기본 50 MiB이며 multipart 요청 한도도 적용합니다.

CLI는 이 API의 클라이언트입니다. 오프라인 `validate`, 실행 대기·종료 코드·timeout은 [시나리오 가이드](scenarios.md)를 참고합니다.

### 패턴 카탈로그 (P1)

`GET /dashboard/v1/pattern-catalog`은 `schemaVersion: 1`, `categories`, `patterns`,
`experiments`를 반환합니다. 각 목록과 `patternIds`는 ID 순서로 정렬됩니다.

- category: `id`, `title`.
- pattern: `id`, `categoryId`, `title`, `problem`, `verification`, `limitations`.
- experiment: `experimentId`, `sourceModuleId`, `sourceCollection`, `classification`.
  분류는 `{categoryId, patternIds}`이며 등록되지 않은 분류는 `null`입니다.

예를 들어 `pipeline.excel-ingestion`의 출처는 `pipeline` / `engineering`, 대표 분야는
`data-processing`, 연결 패턴은 `bounded-queue`, `chunked-write`, `staged-pipeline`입니다.
초기 사전은 7개 분야·18개 패턴입니다. 기본 구성의 활성 실험은 3개이며 Career를 모두
등록하면 16개입니다. 정의 사전에는 비활성 실험과 관련된 패턴도 포함됩니다.

이 API는 실행 가능 여부를 보장하지 않습니다. 기존 dashboard readiness 응답을 확인합니다.
분류는 현재 카탈로그 해석이며 과거 Run에 저장된 정보가 아닙니다. 기존 descriptor,
시나리오 hash, Run/export 형식은 바꾸지 않습니다. v0.1.0 서버에는 이 API가 없으므로
새 클라이언트는 404일 때 기존 `/modules` 탐색으로 돌아갑니다(P2 `/app/catalog`).
자세한 계약은 [패턴 플랫폼 설계](pattern-platform-design.md)를 참고합니다.

## 2. Run 상태와 페이지 조회

| Method | 경로 | 응답의 주요 필드 |
| --- | --- | --- |
| GET | `/runs?limit=100&experimentType=...` | `runs`, `nextBefore` |
| GET | `/runs/{runId}` | 상태·시각·`runMetadata`·`summary`·`failureMessage` |
| GET | `/runs/{runId}/metrics?after=-1&limit=100` | `metrics`, `nextAfter` |
| GET | `/runs/{runId}/traces?after=-1&limit=100` | trace metric만 필터링한 `metrics`, `nextAfter` |
| GET | `/runs/{runId}/dead-letters?after=-1&limit=100` | `runId`, `rows`, `nextAfter` |

```bash
RUN_ID='실제-run-id'
curl --fail --silent --show-error "http://localhost:8080/runs/$RUN_ID"
curl --fail --silent --show-error "http://localhost:8080/runs/$RUN_ID/metrics?after=-1&limit=100"
```

### 반복 묶음(series)

| Method | 경로 | 설명 |
| --- | --- | --- |
| POST | `/run-series?repetitions=5&warmup=1` | `POST /runs`와 같은 시나리오 문서(JSON/YAML, Excel은 multipart `scenario`+`file`). 202와 `seriesId`, `definitionSha256` |
| GET | `/run-series?experimentType=...&limit=20` | 최신순 묶음 목록(limit 1–100) |
| GET | `/run-series/{seriesId}` | `series`, `members`(position·runId·warmup·status), `measuredSucceeded`, `measures`(measure별 `statistics`) |
| PUT | `/run-series/{seriesId}/baseline` | 성공하고 측정 반복이 3회 이상인 묶음을 그 실험·시나리오 ID의 기준선으로 지정(교체). 조건 미달은 409 |
| GET | `/run-series/baselines?limit=50` | 지정된 기준선 묶음, 최근 지정순 |
| GET | `/run-series/{seriesId}/regression?tolerancePct=5` | 같은 실험·시나리오 ID의 기준선과 비교. `overall`(PASS·REGRESSION·INSUFFICIENT·NO_BASELINE)과 measure별 `direction`, `changePct`, `separation`, `verdict` |
| GET | `/run-series/compare?baseline=...&candidate=...` | 같은 실험의 두 묶음을 measure별로 비교. `sameDefinition`, `medianChangePct`, `separation` |

- 반복은 하나씩 순서대로 실행한다. warmup Run도 일반 Run으로 저장하지만 통계에서 제외한다. 반복 수는 정의 hash에 들어가지 않는다.
- `statistics`는 `count`, `min`, `max`, `mean`, `median`, `p95`(nearest rank), `standardDeviation`(표본), `coefficientOfVariation`이다. 값이 없으면 null이며 0으로 채우지 않는다.
- `separation`은 보수적 판정이다. 어느 쪽이든 성공한 측정 반복이 3개 미만이면 `INSUFFICIENT`, 관측 범위(min–max)가 겹치면 `OVERLAPPING`(차이 불분명), 모든 반복이 상대 범위 밖이면 `SEPARATED`다. 통계적 유의성 검정이 아니다.

```bash
curl --fail --silent --show-error -H 'Content-Type: application/yaml' \
  --data-binary @experiments/concurrency/queue-contention/scenarios/small-buffer.yaml \
  'http://localhost:8080/run-series?repetitions=5&warmup=1'
```

`summary`는 `counters`, `gauges`, `details` map이며 필드는 실험군마다 다릅니다. `runMetadata`는 `runId`, `parameters`, `environment`, `git`을 담습니다. 시각의 `*EpochMs`는 epoch milliseconds입니다.

| 상태 | 의미 |
| --- | --- |
| QUEUED | 기록됐지만 실행 시작 전 |
| RUNNING | 실행 중 |
| SUCCEEDED | 실행과 증거 마무리 성공; Excel 검증 오류는 있을 수 있음 |
| FAILED | 실행/증거 처리 실패; 부분 집계를 확인 |
| INTERRUPTED | coordinator 중단 또는 재시작 복구로 종료 처리 |

페이지 규칙:

- Run은 최신순이며 `nextBefore`를 다음 `before`에 넣습니다. 실험 필터도 유지합니다.
- metric은 sequence 오름차순이며 `nextAfter`를 다음 `after`에 넣습니다. 경계는 배타적입니다.
- cursor가 null이면 끝입니다. 페이지가 정확히 limit 크기일 때 마지막 빈 페이지가 나올 수 있습니다.
- trace는 혼합 원본 metric을 스캔합니다. **metrics가 비어도 nextAfter가 있으면 계속 조회**합니다.
- 기본 limit은 min(100, 서버 page-limit), 최대는 기본 500입니다. dead-letter는 추가로 최대 100입니다.
- dead-letter는 종료 Run만 조회합니다. rowIndex는 Excel 0 기반 행 번호이며 `stage`, `reason`, `cells`를 포함합니다.

## 3. 비교·export·artifact

| Method | 경로 | 용도 |
| --- | --- | --- |
| GET | `/runs/compare?baseline=...&candidate=...` | 조건 차이·주의사항·단위 포함 summary 비교 |
| GET | `/runs/{runId}/export` | 종료 Run의 NDJSON |
| GET | `/runs/{runId}/artifacts` | artifact 참조 목록 |
| PUT | `/runs/{runId}/artifacts/{artifactId}` | 파일 참조 metadata 등록 |
| GET | `/runs/{runId}/artifacts/{artifactId}/download` | 종료 Run의 관리 JFR/log 다운로드 |
| GET | `/runs/ui/options` | UI용 page 크기·다운로드 상한 |

```bash
BASELINE_ID='기준-run-id'
CANDIDATE_ID='비교-run-id'
curl --fail --silent --show-error \
  "http://localhost:8080/runs/compare?baseline=$BASELINE_ID&candidate=$CANDIDATE_ID"
curl --fail --silent --show-error --output run-evidence.ndjson \
  "http://localhost:8080/runs/$RUN_ID/export"
tail -n 1 run-evidence.ndjson
shasum -a 256 run-evidence.ndjson
```

export는 `run` → `metric` → `artifact-reference` → `end` 레코드 순서입니다. 끝의 Run ID·metricCount·artifactCount를 확인합니다. metric 개수 0은 짧은 실행에서 가능하며 누락된 end는 불완전 전송입니다. 파일 bytes·입력 원본·dead-letter 행은 export하지 않습니다.

artifact 등록 body 예(파일 내용은 업로드하지 않음):

```json
{
  "relativePath": "profiles/example.jfr",
  "mediaType": "application/octet-stream",
  "byteSize": 123,
  "sha256": "실제-파일의-64자리-소문자-sha256"
}
```

실제 크기/hash로 바꾸어야 합니다. 참조 등록은 내용 검증이 아니며 일반 `profiles/` 참조는 관리 다운로드 대상이 아닙니다. Run당 최대 100개, 요청 body 최대 8 KiB입니다. ID별 동일 내용의 재등록만 허용합니다. 관리 파일 경로·checksum·심볼릭 링크·다운로드 오류의 상세 계약은 [증거 가이드](run-evidence.md)에 있습니다.

## 4. 실험별 호환 API

### Excel

| Method | 경로 | 용도 |
| --- | --- | --- |
| POST | `/ingest/excel?mode=PIPELINE` | `file` multipart 업로드; SINGLE_THREAD도 지원 |
| GET | `/ingest/jobs` | `{jobs}` 최신 최대 100개 |
| GET | `/ingest/jobs/{jobId}` | 행 수·오류 집계·실패 이유·metadata |
| GET | `/ingest/jobs/{jobId}/timeline` | `{jobId,status,snapshots}` 진행 preview |
| GET | `/ingest/jobs/{jobId}/metrics` | 공통 metric preview |
| GET | `/ingest/db/count` | `{count}` 현재 ingestion 테이블 수 |
| GET | `/ingest/db/rows?limit=200` | 현재 행 최신순; limit은 1–2000으로 보정 |

```bash
curl --fail --silent --show-error \
  -F 'file=@experiments/pipeline/excel-ingestion/scenarios/fixtures/sample.xlsx' \
  'http://localhost:8080/ingest/excel?mode=SINGLE_THREAD'
```

202 응답은 `{jobId, mode}`입니다. jobId와 Run ID는 같습니다. 이 API는 서버의 `pipeline.*` 설정을 사용하며 선언적 정의 hash를 새로 붙이지 않습니다.

### Queue와 IO

```bash
curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -d '{"scenarioId":"queue-demo","queueCapacity":2,"producerThreads":2,"consumerThreads":1,"itemsPerProducer":100,"consumerDelayMs":2}' \
  http://localhost:8080/experiments/concurrency/queue-contention

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -d '{"scenarioId":"io-demo","requests":12,"delayMs":20,"pinning":false,"pinDelayMs":20}' \
  http://localhost:8080/experiments/io/endpoint-comparison
```

둘 다 202 `{jobId, experimentType, scenarioId}`를 반환합니다. `/experiments/jobs`, `/experiments/jobs/{jobId}`, `/experiments/jobs/{jobId}/metrics`로 조회합니다. 기존 작업 API는 QUEUED→RUNNING, INTERRUPTED→FAILED로 호환 표시하므로 정확한 lifecycle은 `/runs/{id}`에서 봅니다.

### IO 단건 endpoint

```bash
curl --fail 'http://localhost:8080/lab/io/virtual-thread/echo?msg=hello&delayMs=20&pinning=false&pinDelayMs=20'
curl --fail 'http://localhost:8080/lab/io/reactive/echo?msg=hello&delayMs=20'
```

text/plain 응답이며 호출 자체는 영속 Run을 생성하지 않습니다. 브라우저 `/lab/io/ui`도 이 endpoint를 사용합니다. `pinning`은 virtual-thread 쪽 조건이고 현재 JDK의 pinning 발생 증명이 아닙니다.

## 5. 오류 해석

| 코드/상태 | 대표 원인 | 다음 확인 |
| --- | --- | --- |
| 400 | 정의/schema/hash·파라미터·페이지 경계 오류 | 해당 API/시나리오의 타입·필수값 |
| 404 | Run/실험/참조/파일 없음 | ID, 서버 origin, 저장 디렉터리 |
| 409 | 실행 중 export/dead-letter/download, artifact 조건 불일치 | 최종 상태, 경로·checksum |
| 413 | 업로드·정의·artifact 다운로드 크기 초과 | 서버/시나리오/파일 제한 |
| 429 | artifact 다운로드 슬롯 포화 | 진행 중 전송 완료 후 재시도 |
| 503 | 저장소 불가, 접수/staging 포화, 파일시스템 기능 미지원 | 서버 로그와 설정 |
| 202 후 FAILED | 비동기 실행 또는 증거 처리 실패 | `/runs/{id}`의 failureMessage·부분 summary |

모든 실패가 접수 HTTP 응답에 나타나지는 않습니다. POST timeout 뒤 무조건 재전송하면 중복 Run을 만들 수 있습니다. [운영·문제 해결](operations.md)을 참고하세요.
