# 실행 증거 저장·조회

앱은 `DurableExperimentRunner`와 별도 H2 file `RunStore`를 사용한다. 기본 파일은 `.lab/runs.mv.db`다. `.lab/`은 Git에서 제외된다. 입력 XLSX 원본과 ingestion 테이블은 이 저장소에 복사하지 않는다.

## 실행과 복구

1. 제출 전에 parameters, JVM/OS, Git provenance를 복사하고 `QUEUED` 행을 저장한다.
2. executor가 거부하면 `FAILED`와 거부 이유를 기록한다. 실행을 시작하면 `RUNNING`으로 전환한다.
3. metric은 실행별 bounded queue → 크기/시간 기준 batch → 별도 DB 트랜잭션으로 기록한다.
4. 종료 시 partial batch까지 저장한 뒤에만 `SUCCEEDED`를 기록한다. 실험 예외나 metric 저장 실패는 `FAILED`와 이유를 남긴다.
5. 앱을 다시 시작하면 남은 `QUEUED`/`RUNNING`을 `INTERRUPTED`로 전환한다. 자동 재실행하지 않는다.

DB가 완전히 접근 불가능하면 실패 상태 자체도 쓰지 못할 수 있다. API는 DB 접근 실패를 HTTP 503으로 노출하고 runner는 `job_id`와 원인을 로그에 남긴다. 이 경우 실행을 성공으로 표시하지 않으며, 저장소 복구 후 앱을 재시작하면 남은 실행을 INTERRUPTED로 처리한다. 프로세스 강제 종료 시 아직 commit되지 않은 metric은 없을 수 있다.

동일 저장 경로는 한 앱만 소유할 수 있다. OS 파일 잠금과 H2 파일 잠금으로 중복 소유를 막는다. 백업은 앱을 정상 종료한 뒤 `.lab/` 전체를 복사한다. `lab_schema`가 v1이 아니면 DDL 변경 전에 시작을 거부한다. 향후 버전은 명시적인 migration이 필요하다.

## 설정

`src/main/resources/application.yml`의 `lab.storage` 기본값:

| 키 | 기본값 | 의미 |
| --- | ---: | --- |
| `root` | `.lab` | evidence DB와 소유 잠금 파일 경로 |
| `queue-capacity` | 1000 | 실행당 대기 metric 수 |
| `batch-size` | 100 | 실행당 최대 insert batch 크기 |
| `flush-interval-ms` | 1000 | 첫 metric 이후 partial batch flush 간격 |
| `max-attempts` | 3 | metric batch의 최초 시도 포함 최대 시도 수 |
| `retry-backoff-ms` | 100 | metric batch 재시도 간 대기 |
| `shutdown-timeout-ms` | 30000 | writer drain 및 각 executor/runner 종료 대기 한도 |
| `page-limit` | 500 | 조회당 최대 run/metric 수 |
| `preview-limit` | 200 | runner 결과와 기존 job API의 최근 metric 수, page-limit 이하 |
| `max-active-runs` | 8 | 동시 실행 상한; 초과 실행은 실패로 기록 |
| `git-directory` | `.` | provenance를 읽을 저장소 경로 |
| `git-timeout-ms` | 2000 | Git 명령당 timeout; 출력 제한 64 KiB |

예: `./gradlew bootRun --args='--lab.storage.root=/tmp/my-lab-evidence'`.

종료 이벤트에서 신규 제출을 막고, 이미 제출된 작업은 executor 종료 대기 동안 처리한다. 이후 runner와 writer가 drain한다. 한도를 넘으면 interrupt와 오류 로그를 남긴다. 전체 앱 종료 시간은 여러 종료 단계의 대기 시간이 합산될 수 있다. 무한 대기를 요구하는 실험의 완료를 보장하지 않는다.

## 조회 API

- `GET /runs?limit=100&experimentType=concurrency.queue-contention`: 최신 실행부터 조회. `runs`와 `nextBefore` 반환.
- `GET /runs?limit=100&before=<nextBefore>`: 다음 페이지. 필터를 사용했다면 다음 요청에도 유지한다.
- `GET /runs/{runId}`: 상태, 생성/시작/완료 시각, metadata, summary, 실패 이유.
- `GET /runs/{runId}/metrics?after=-1&limit=100`: metric을 sequence 오름차순으로 조회. `metrics`와 `nextAfter` 반환.
- 다음 metric 페이지는 `after=<nextAfter>`로 조회한다. null cursor면 끝이다. 마지막 페이지가 정확히 limit 크기라면 빈 다음 페이지가 나올 수 있다.

cursor는 DB의 단조 증가 sequence이며 배타적 경계다. 이름·시간 문자열로 정렬하지 않는다. 기본 page 크기는 100 또는 설정된 page-limit 중 작은 값이다. 잘못된 크기/경계는 400, 없는 run은 404다. 전체 metric 목록을 한 응답에 쌓지 않는다.

기존 `/experiments/jobs*`, `/ingest/jobs*`도 DB에서 복원한다. 목록은 최대 최신 100개, timeline은 preview-limit 이내다. QUEUED는 기존 화면에 RUNNING, INTERRUPTED는 FAILED로 표시된다. Excel 화면의 원래 progress 형식은 공통 metric에서 복원한다. 원본 실험 데이터 테이블은 별도 in-memory DB이므로 재시작 후 DB row 화면이 비어 있어도 과거 실행 결과는 남는다.

`POST /runs`로 registry의 JSON/YAML 정의를 제출할 수 있다. Excel은 scenario/file multipart를 사용한다. [시나리오 가이드](scenarios.md)의 CLI는 같은 API를 사용하며, 기존 실험별 POST도 유지된다. 기존 `lab_artifact` 테이블에 사용자 제공 artifact 참조 metadata를 등록/조회한다. 원본 파일 등록/다운로드는 제공하지 않는다. 비교와 NDJSON export는 [증거 가이드](run-evidence.md)를 참조한다.

## 관측과 제한

완료 summary의 `storage.*` counter에는 written metrics, batches, retries, failures, 현재/최대 queue depth, publication 대기 nanoseconds, 총 batch write nanoseconds, preview limit이 포함된다. 로그에는 job_id, stage, reason을 기록한다. 이 카운터 namespace는 플랫폼용으로 예약한다.

메트릭 writer가 보유하는 관측 수는 실행당 queue-capacity + batch-size이며 결과 preview는 preview-limit 이하이다. 활성 실행 수와 executor 대기열도 제한된다. 이것이 앱 전체의 메모리 상한 보장은 아니다. 기존 XLSX byte[] 업로드, 파이프라인 validation 오류 수집, metric 한 개의 필드 크기 등은 별도 제약이 필요하다.

`MetricWriterPerformanceTest`는 200개의 metric, write당 고정 3ms 지연, queue 32/batch 20 조건에서 per-point 쓰기와 batch 쓰기를 비교한다. 버퍼 상한과 모든 metric 저장을 검증하고 raw 결과를 `build/reports/metric-writer-benchmark.json`에 생성한다. 실제 DB/Excel 처리량의 보편적 개선 수치로 해석하지 않는다. 이 보고서는 build 산출물이므로 clean 시 삭제된다. 애플리케이션 실험 증거는 `.lab/`에 저장된다.

## 테스트

`./gradlew test`는 저장소 재개방과 스키마/소유권 검사, 실제 앱 재시작, 제출 거부, 저장 실패, 크기·시간·종료 flush, backpressure, 재시도 sequence와 중복 방지, timeout, preview/page 제한, 제어된 성능 비교를 포함한다. 테스트 앱은 임시 저장 경로를 사용하며 실제 `.lab/`을 변경하지 않는다.
