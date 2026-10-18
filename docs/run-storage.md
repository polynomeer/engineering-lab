# 실행 증거 저장·조회

앱은 `DurableExperimentRunner`와 별도 H2 file `RunStore`를 사용한다. 기본 파일은 `.lab/runs.mv.db`다. `.lab/`은 Git에서 제외된다. 입력 XLSX 원본과 ingestion 테이블은 이 저장소에 복사하지 않는다.

## 실행과 복구

1. 제출 전에 parameters, JVM/OS, Git provenance를 복사하고 `QUEUED` 행을 저장한다.
2. executor가 거부하면 `FAILED`와 거부 이유를 기록한다. 실행을 시작하면 `RUNNING`으로 전환한다.
3. metric은 실행별 bounded queue → 크기/시간 기준 batch → 별도 DB 트랜잭션으로 기록한다.
4. 종료 시 partial batch까지 저장한 뒤에만 `SUCCEEDED`를 기록한다. 실험 예외나 metric 저장 실패는 `FAILED`와 이유를 남긴다.
5. 앱을 다시 시작하면 남은 `QUEUED`/`RUNNING`을 `INTERRUPTED`로 전환한다. 자동 재실행하지 않는다.

DB가 완전히 접근 불가능하면 실패 상태 자체도 쓰지 못할 수 있다. API는 DB 접근 실패를 HTTP 503으로 노출하고 runner는 `job_id`와 원인을 로그에 남긴다. 이 경우 실행을 성공으로 표시하지 않으며, 저장소 복구 후 앱을 재시작하면 남은 실행을 INTERRUPTED로 처리한다. 프로세스 강제 종료 시 아직 commit되지 않은 metric은 없을 수 있다.

동일 저장 경로는 한 앱만 소유할 수 있다. OS 파일 잠금과 H2 파일 잠금으로 중복 소유를 막는다. 백업은 앱을 정상 종료한 뒤 `.lab/` 전체를 복사한다. 현재 schema는 v4이며 v1–v3은 아래의 additive 이행으로 갱신한다. 지원하지 않는 버전은 DDL 변경 전에 시작을 거부한다. 향후 버전도 명시적인 migration이 필요하다.

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
| `max-active-runs` | 8 | 실행기 전체를 합산한 동시 Run 상한; 초과 실행은 실패로 기록 |
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

`POST /runs`로 registry의 JSON/YAML 정의를 제출할 수 있다. Excel은 scenario/file multipart를 사용한다. [시나리오 가이드](scenarios.md)의 CLI는 같은 API를 사용하며, 기존 실험별 POST도 유지된다. 기존 `lab_artifact` 테이블에 사용자 제공 artifact 참조 metadata를 등록/조회한다. 등록 API는 원본 파일을 업로드하지 않는다. 내장 수집기의 관리 JFR/log 파일은 종료 Run의 검증된 다운로드 API로 제공한다. 비교와 NDJSON export는 [증거 가이드](run-evidence.md)를 참조한다.

## 관측과 제한

완료 summary의 `storage.*` counter에는 written metrics, batches, retries, failures, 현재/최대 queue depth, publication 대기 nanoseconds, 총 batch write nanoseconds, preview limit이 포함된다. 로그에는 job_id, stage, reason을 기록한다. 이 카운터 namespace는 플랫폼용으로 예약한다.

메트릭 writer가 보유하는 관측 수는 실행당 queue-capacity + batch-size이며 결과 preview는 preview-limit 이하이다. 활성 실행 수와 executor 대기열도 제한된다. 이것이 앱 전체의 메모리 상한 보장은 아니다. HTTP 업로드와 validation 미리보기에도 아래 상한을 적용한다. metric 한 개의 필드 크기 제한과 전체 프로세스 메모리 보장은 별개다. 전체 ingestion은 [benchmark](pipeline-benchmark.md)에서 지정 입력의 128 MiB 제한 힙 완료를 측정했으며 RSS나 임의 입력의 메모리 상한을 보장하지 않는다.

`MetricWriterPerformanceTest`는 200개의 metric, write당 고정 3ms 지연, queue 32/batch 20 조건에서 per-point 쓰기와 batch 쓰기를 비교한다. 버퍼 상한과 모든 metric 저장을 검증하고 raw 결과를 `build/reports/metric-writer-benchmark.json`에 생성한다. 실제 DB/Excel 처리량의 보편적 개선 수치로 해석하지 않는다. 이 보고서는 build 산출물이므로 clean 시 삭제된다. 애플리케이션 실험 증거는 `.lab/`에 저장된다.

## 테스트

`./gradlew test`는 저장소 재개방과 스키마/소유권 검사, 실제 앱 재시작, 제출 거부, 저장 실패, 크기·시간·종료 flush, backpressure, 재시도 sequence와 중복 방지, timeout, preview/page 제한, 제어된 성능 비교를 포함한다. 테스트 앱은 임시 저장 경로를 사용하며 실제 `.lab/`을 변경하지 않는다.

## Validation dead-letter 저장과 조회

dead-letter 테이블은 v2에서 추가했으며 v3에서도 그대로 유지한다. v1에서 시작하면 `lab_dead_letter`를 추가한 뒤 v3 execution/check 이행을 적용한다. 기존 Run/metric/artifact/dead-letter는 보존한다. 이행·백업·되돌리기는 아래 v3 절차를 따른다.

앱에서 실행하는 Excel의 검증 실패 행은 동기적으로 즉시 저장된다. 신규 Run metadata의 `evidence.dead-letter.mode=DATABASE_V1`과 `evidence.dead-letter.maxRecordBytes=65536`으로 저장 계약을 구분한다. 이전 Run이나 직접 사용하는 LocalExperimentRunner에는 과거 실패 행을 소급 생성하지 않는다.

`GET /runs/{runId}/dead-letters?after=-1&limit=100`은 `{runId, rows, nextAfter}`를 반환한다. 각 행은 `rowIndex`, `stage=validation`, `reason`, `cells`를 포함한다. rowIndex는 첫 시트의 0 기반 인덱스이므로 Excel 표시 행 번호는 +1이다. 헤더인 0번 행은 수집 대상이 아니다. 셀은 파서가 읽은 문자열이며 수식·스타일·원본 XLSX 자체를 복제하지 않는다.

다음 요청은 `after=<nextAfter>`로 조회한다. 행 번호 오름차순, 배타적 cursor이며 null이면 끝이다. limit은 1부터 `min(lab.storage.page-limit, 100)`, 기본값도 이 상한이다. 병렬 검증의 순서 차이로 누락되지 않도록 QUEUED/RUNNING Run은 409, 잘못된 크기/경계는 400, 없는 Run은 404다. 실패·중단 Run에서도 이미 저장된 행은 조회할 수 있다. 기존 버전의 빈 목록은 검증 오류가 없었다는 증거가 아니다.

행 하나의 UTF-8 JSON은 최대 64 KiB, 셀 수는 최대 16384이며 직렬화 전 문자 수에도 상한을 둔다. 초과하거나 저장이 실패하면 내용을 자르거나 버리지 않고 Run을 실패 처리한다. 저장 확인 전의 행과 실패 중 큐에 남은 행까지 보존하는 기능은 아니다. insert 오류의 원본 행 출력·재처리, 보관 기간/용량 관리도 후속 범위다.

실패 행의 실제 셀 내용이 저장되므로 `.lab/`과 API 접근은 원본 입력과 같은 수준으로 관리한다. 자동 마스킹은 하지 않는다. 로그에는 job_id·stage·reason·row_index만 남긴다. validation 원본 미리보기는 `pipeline.error-preview-limit`만 보유하며 전체 건수와 사유별 집계는 별도로 유지한다. HTTP 업로드는 아래 임시 파일 경로를 사용한다.

## 업로드 수명과 메모리 상한

두 HTTP 업로드 경로는 요청 파일 전체를 byte[]로 읽지 않는다. servlet multipart threshold는 0으로 두어 디스크를 사용하고, 애플리케이션은 8 KiB 버퍼로 별도 임시 파일과 SHA-256을 만든다. 애플리케이션 staging 슬롯은 프로세스당 16개이며 읽는 중·대기 중·실행 중 파일을 포함한다. 각 파일은 최대 50 MiB(선언적 시나리오는 더 작은 max-input-bytes 적용)다. 초과는 413, 슬롯 포화는 503이며 새 Run 생성 전에 거부한다.

정상 완료·실행 실패·정의/hash 검증 실패·prepare 실패·executor 거부 시 파일 소유자가 삭제한다. 파서는 기존 InputStream 계약을 유지하기 위해 최대 50 MiB 임시 파일을 하나 더 사용하며 finally에서 삭제한다. 업로드/파서 파일은 시스템 임시 디렉터리에 `engineering-lab-upload-`/`engineering-lab-parser-` 접두사로 생성된다. 강제 프로세스 종료나 실행되지 않은 대기 작업의 shutdown 취소에서는 잔여 파일이 생길 수 있다. 앱 정지 후 잔여 파일을 정리할 수 있으며 다른 프로세스의 진행 중 파일을 자동 삭제하지 않는다. 임시 파일 삭제 실패는 예외로 전달하고 슬롯을 재사용하지 않는다.

XLSX 허용 상한은 ZIP entry 1024개, 압축 해제 총량 256 MiB, worksheet XML을 제외한 metadata 총량 8 MiB(공유 문자열·스타일 포함)다. POI 메모리 할당 전에 스트리밍 검사하며, 실제 worksheet는 SAX로 처리한다. 행당 셀 16384개/문자 65536개, 셀 XML 텍스트 누적 65536개를 넘기면 Run이 실패한다. 큰 공유 문자열 테이블을 사용하는 파일은 압축 크기가 작아도 거부될 수 있다. 상한을 조용히 자르거나 무시하지 않는다.

원본 오류 행 미리보기는 기본 100행, `pipeline.error-preview-limit` 또는 시나리오 `errorPreviewLimit`로 0–1000을 설정한다. `getValidationErrors()`는 이 미리보기이고 `getValidationErrorCount()`/사유별 집계가 전체 오류 수다. API summary의 기존 오류 건수와 사유 집계, 영속 dead-letter에는 모든 확인된 실패 행을 반영한다. 로컬 byte[] 생성자는 호환용이며 HTTP 경로에서는 사용하지 않는다. 이 변경은 파일·행 개수에 비례한 무제한 보유를 제거하며 JVM 전체 heap/성능 수치는 최종 성능 단계에서 측정한다.

## 실패·중단 시 파이프라인 종료

`pipeline.shutdown-timeout-ms`/시나리오 `shutdownTimeoutMs`는 기본 30000 ms, 범위 1–60000 ms다. PIPELINE의 입력 종료/실패 뒤 END 전달·validator 완료·insert 완료 전체에 한 번 적용하고, executor 정리에는 별도의 동일 길이 deadline을 적용한다. 따라서 입력 처리가 반환한 뒤 최대 대기 예산은 대략 두 배이며 JVM scheduling 시간은 추가될 수 있다. 입력 읽기 자체의 전체 실행 timeout은 아니다.

입력 파싱 실패나 coordinator 인터럽트에서는 추가 입력을 멈추고 이미 접수한 큐의 유효 행·검증 실패 행을 drain하며 partial batch도 저장한다. drain 중 인터럽트는 기록하고 일시 해제한 뒤 정리가 끝나면 다시 설정한다. SINGLE_THREAD도 입력 실패로 끝난 경우 기존 안전한 partial batch를 flush하지만, callback/DB/dead-letter 실패로 끝난 batch는 다시 시도하지 않는다. SINGLE_THREAD의 동기 DB 호출에는 executor 종료 deadline이 적용되지 않는다.

DB·dead-letter·worker 실패 또는 drain timeout이면 추가 처리를 중단하고 worker에 interrupt를 전달한다. worker 종료를 확인한 뒤 실패 결과를 저장한다. 기본 진행 callback도 종료를 기다리며 비동기 callback 실패가 성공 Run으로 숨겨지지 않는다. 입력 복사·ZIP 검사도 읽기 사이에서 인터럽트를 확인한다.

실패 Run summary는 `rows.produced`, `rows.inserted`, `rows.validationErrors`, `retry.count`, `rows.unconfirmed`, `shutdown.unfinishedExecutors`와 기존 검증 사유 집계를 보존한다. `rows.unconfirmed=max(0, produced-inserted-validationErrors)`는 접수/저장 확인 기준이며 읽지 않은 입력이나 커밋 결과 불확실성을 해결하지 않는다. 0이어도 전체 파일 처리가 끝났다는 의미는 아니다.

`shutdown.progress=QUIESCENT`는 관련 executor 종료가 확인됐다는 뜻이다. 비협조적 드라이버 등으로 deadline을 넘긴 경우 `UNCONFIRMED`와 남은 executor 수를 기록하며 성공으로 보고하지 않는다. 이때 늦은 DB 변경이 가능하므로 snapshot 집계를 최종 확정값으로 해석하거나 자동 재실행하지 않는다. 강제 프로세스 종료, 실행되지 않은 대기 작업의 임시 파일, 미확인 행의 영속 재처리는 이 계약에 포함되지 않는다.

## 실행기 선택과 중앙 제한

현재 앱의 실행기는 `IN_PROCESS` 하나다. 서버 내부 `ExperimentExecutors` 등록은 실험 ID를 실행기에 연결하며, 시나리오 파라미터로 실행기를 바꿀 수 없다. 추가 실행기는 전체 `max-active-runs`와 등록별 상한을 모두 적용받고, 배타 등록된 실험은 다른 Run이 없을 때만 시작한다. 현재는 제한 충돌을 기다리는 별도 큐가 없으며 접수된 Run을 FAILED로 기록한다. 기존 제출용 task executor의 큐 설정은 그대로다.

중앙 제한은 스레드 수 대신 Run ID를 센다. 중복 실행 요청은 진행 중인 원래 Run을 변경하지 않는다. 신규 접수 중단 뒤 이미 접수한 작업은 종료 대기 시작 전까지 실행할 수 있고, 종료 대기는 기존 storage shutdown timeout을 사용한다. 실제 Java 21 worker 실행·worker 대기 큐·환경 handshake 연결은 후속 단계다. 기존 `runMetadata.environment/git`은 계속 서버 환경·코드를 뜻한다.

## v3 execution/check 저장 계약

`lab_execution`과 `lab_check`를 추가했다. 앱에 등록된 신규 실험의 자동 기록과 execution/check HTTP 조회·별도 schemaVersion=2 export를 연결했다. [API 계약](run-evidence.md)을 참조한다. capability/catalog extension과 worker 연결은 후속 단계다. 기존 Run과 catalog를 전달하지 않은 직접 runner 호출은 execution이 없을 수 있다. 없는 정보를 IN_PROCESS 성공이나 checks 통과로 소급 생성하지 않는다.

- execution definition은 실행 종류(IN_PROCESS/LOCAL_JVM), module ID, protocol version, 측정 범위를 저장한다. IN_PROCESS는 protocol 0/CONTROLLER_PROCESS, LOCAL_JVM은 양수 protocol/WORKER_PROCESS를 사용한다.
- worker는 실제 JVM/OS, Git provenance, 배포물 SHA-256이다. LOCAL_JVM의 RUNNING/PREPARING에서만 최초 등록하고 동일 값의 재등록만 허용한다. handshake 전에는 null이며 서버 환경으로 대체하지 않는다. 기존 `runMetadata.environment/git`은 수정하지 않는다.
- phase는 PREPARING → MEASURING → FINALIZING 순서다. 같은 단계 재전송은 허용하되 건너뛰기·역행은 거부한다. 외부 실행은 worker가 확인되어야 측정 단계와 check 기록을 허용한다.
- check는 sequence, checkId, PASS/FAIL, expected/observed/description을 저장한다. `(runId, sequence)`와 `(runId, checkId)`는 불변이다. 동일 sequence/내용의 재전송은 중복 없이 처리하고 충돌은 거부한다. check의 FAIL 자체가 Run 상태를 자동 변경하지는 않으며 executor가 최종 결과를 판정한다.
- check sequence는 0–99,999, Run당 최대 100,000개다. 세 설명 필드는 각각 최대 4,096자이며 인코딩된 레코드는 최대 64 KiB다. 조회는 `min(page-limit, 100)`개 이하, sequence 오름차순·배타 cursor(-1부터)다. 실행 중 뒤늦게 낮은 sequence가 기록될 수 있어 전체 페이지 순회는 producer 종료 후 수행한다. 전체 목록을 메모리에 모으지 않는다.
- execution definition과 worker JSON도 각각 최대 64 KiB다. worker 환경 문자열은 각각 256자, Git commit은 128자/error는 1,024자 이하로 직렬화 전에도 제한한다. 초과 데이터는 잘라 저장하지 않는다. 이는 전체 workspace/DB 용량 quota를 구현한 것이 아니다.
- `finishExecutionEvidence`는 executor가 최종 결과·종료·개수·flush를 확인한 뒤 호출하는 저장 계약이다. FINALIZING에서 완료 표시를 고정하며 이후 check 추가를 거부한다. execution이 등록된 Run은 증거 미완료 상태로 SUCCEEDED가 될 수 없다. worker protocol 검증 자체는 아직 구현하지 않았다.
- terminal Run의 execution/check 변경은 거부한다. 재시작 복구는 미종료 Run을 INTERRUPTED로 바꾸며 같은 트랜잭션에서 evidenceComplete를 false로 돌린다. 이미 저장된 worker/check와 단계는 부분 증거로 유지한다.

## v4 반복 묶음(series) 저장 계약

`lab_series`와 `lab_series_run`을 추가했다. 같은 정의를 여러 번 실행한 묶음과 그 구성원을 저장한다. 각 반복은 일반 Run이며 `lab_run`·metadata는 바꾸지 않는다. 묶음 정보를 Run parameter에 넣지 않으므로 기존 Run 비교가 반복 번호를 조건 차이로 보지 않는다.

- 묶음은 실험·시나리오·정의 SHA-256, 측정 반복 수(1–50), warmup 수(0–10), 상태, 생성·종료 시각, 실패 사유(최대 1,000자)를 저장한다.
- 구성원은 `(series_id, position)`이 기본 키이고 `run_id`는 한 묶음에만 속한다. position은 warmup부터 0으로 시작하며 `warmup + repetitions`보다 작아야 한다. RUNNING 묶음에만 추가할 수 있다.
- 상태는 Run과 같은 값이다. 모든 측정 반복이 성공하면 SUCCEEDED, 하나라도 아니면 FAILED다. 재시작 시 미종료 묶음은 INTERRUPTED가 되며 자동으로 이어 실행하지 않는다.
- 통계는 저장하지 않고 조회 때 계산한다. 반복 Run에 기록된 측정 계약의 measure 값을, 성공한 비-warmup Run에서만 읽는다.

## v4 이행

v1–v3 DB를 열면 아래 v3 절차와 같은 규칙으로 `<storage-root>/backups/schema-v<1|2|3>-before-v4-<UUID>.zip`을 먼저 만든다. 백업 실패 시 DDL 전에 시작을 중단한다. v1/v2는 v3 테이블과 v4 테이블을 차례로 추가한 뒤 version을 4로 바꾼다. 이전 서버로 되돌릴 때는 v4 DB를 열지 않고 아래 4–6번처럼 업데이트 전 복사본이나 자동 백업을 빈 디렉터리에 복원한다. v4 원본은 보관한다.

## v3 이행·백업·되돌리기

1. 운영 업데이트 전 앱을 정상 종료하고 저장 디렉터리 전체(DB와 JFR/log 파일 포함)를 별도 위치에 복사한다. 사용자 `.lab/`에 대한 이행은 앱을 새 버전으로 시작할 때 수행된다. 테스트는 임시 DB만 사용한다.
2. v1/v2를 열면 소유권 잠금 아래 H2의 일관된 DB 백업을 먼저 생성한다. 경로는 `<storage-root>/backups/schema-v<1|2>-before-v3-<UUID>.zip`이다. 생성 중 파일은 `.zip.partial`이며 완성 뒤 atomic rename한다. 백업 생성·게시 실패 시 DDL 전에 시작을 중단한다. 기존 백업을 덮어쓰지 않는다. 자동 백업에는 DB만 포함되므로 artifact 복원에는 1번의 전체 복사본이 필요하다.
3. 테이블 추가 뒤 마지막으로 schema version을 3으로 바꾼다. H2 DDL은 전체 트랜잭션 rollback을 보장하지 않으므로 테이블 생성 후 중단되면 같은 버전으로 재시작해 나머지를 적용한다. 완료한 v3 재개방은 이행이나 추가 백업을 수행하지 않는다. 반복 실패로 생긴 백업은 운영자가 확인해 관리한다.
4. 이전 서버로 되돌릴 때는 v3 DB를 그대로 열지 않는다. 필요한 새 Run의 기존 API export와 v3 저장 디렉터리 원본을 별도 보존하고 앱을 종료한다. 신규 `/dashboard/v1/runs/{id}/export`는 execution/check를 포함하지만 기존 `/runs/{id}/export`는 포함하지 않는다. export는 DB 복원 도구가 아니므로 **v3 DB 자체도 반드시 보관**한다.
5. 별도의 빈 저장 디렉터리에 업데이트 전 전체 복사본을 복원하고 이전 서버의 storage root로 지정한다. 자동 DB 백업을 사용할 경우 ZIP의 `runs.mv.db`를 빈 디렉터리에 풀고, 백업 시점에 대응하는 artifact 파일도 전체 복사본에서 복원한다. 현재 DB 위에 실행 중 덮어쓰거나 schema version 숫자만 낮추지 않는다.
6. 이전 서버에서 기존 Run·metric·dead-letter·artifact를 확인한다. v3 이행 뒤 생성된 Run은 v2 백업에 없으므로 보존한 v3 원본은 삭제하지 않는다. 자동 복원 테스트는 ZIP을 별도 위치에 풀어 schema 2와 원시 증거 일치를 확인한다.

## 운영 배포의 자동 백업

고정 로컬 배포와 LaunchAgent를 사용하는 경우 [백업·복원 자동화](local-backup.md)의
정기 snapshot을 사용할 수 있다. 서비스 정지·저장소 잠금·DB와 artifact 동시 복사·hash
검증·서비스 재개를 묶으며, 복원은 기존 저장소를 덮어쓰지 않는 새 경로에만 허용한다.
