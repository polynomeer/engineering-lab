# 관측 schema, JVM 스냅샷, Trace와 JFR

5단계 첫 작업은 공통 단위 catalog와 선택적 JVM 시작/종료 관측이다. 주기적 JVM sampling도 선택적으로 지원한다. 상한이 있는 명시적 trace도 지원한다. 선택적 JFR BASIC_V1 녹화도 지원한다. Run lifecycle 로그도 선택적으로 수집한다. 원문 서버 로그 수집과 외부 인프라는 후속 확장이다.

## Metric catalog v2

`GET /metrics/schema`는 이름·단위·범위(run/process/span)·semantics·aggregation을 제공한다. 비교 API도 같은 catalog를 쓴다. ms/ns, bytes/MiB, percent, rate를 구분하며 알 수 없는 이름은 unknown이다. 기존 MetricPoint와 DB schema는 변경하지 않는다. legacy counters/gauges는 저장 컨테이너일 뿐 의미론적 counter/gauge 보장은 아니다(예: throughput rate가 counters에 존재).

기존 heap `Mb` 필드는 실제 1024² byte 기준이므로 MiB로 설명한다. 기존 metric의 placeholder/구현 누락을 catalog가 실제 계측으로 바꾸지는 않는다. 특히 pipeline connection/lock/retry 지표는 구현 상태를 함께 확인한다. schema version은 새로운 Run의 parameters에 남기며 기존 실행에는 소급 기록하지 않는다.

## JVM 경계 관측 — 구현됨

- 기본값 `lab.observation.jvm-enabled=false`. true이며 jvm-interval-ms=0이면 실행 직전/직후 각 한 번, 최대 두 관측을 기존 bounded metric writer에 보낸다.
- MXBean의 heap used/committed/max bytes, live platform thread 수, 누적 GC collection count/time을 수집한다. 지원하지 않는 음수 값은 0으로 바꾸지 않고 생략한다.
- 프로세스 전체 값이다. 동시 실험·서버·writer 작업이 섞이며 Run별 할당량/CPU/virtual thread 수/GC pause 분포를 의미하지 않는다. GC time은 JVM 시작 후 누적 collection time이며 wall-clock pause latency와 다르다.
- 추가 scheduler, 작업 queue, 주기 thread를 만들지 않는다. 시작/종료 관측 사이의 peak는 관측하지 못한다.
- 수집 시간(ns)과 sample 수를 summary에 기록한다. 수집/저장 실패는 성공으로 숨기지 않는다. 종료 관측 후 writer를 drain하므로 완료 이후 관측을 게시하지 않는다.
- labels는 source/scope/phase/schemaVersion만 사용하고 스택·thread 이름·환경변수·JVM 인자·파일을 수집하지 않는다.
- 켜짐/꺼짐과 schema version을 Run parameters에 기록해 비교 화면에서 조건 차이를 볼 수 있게 한다.

## 사용과 검증

```bash
./gradlew bootRun --args='--lab.observation.jvm-enabled=true'
./gradlew -q lab --args='run experiments/concurrency/queue-contention/scenarios/small-buffer.yaml --wait'
```

`GET /runs/{id}/metrics`와 NDJSON export에서 source=jvm.boundary, phase=start/end를 찾는다. 기존 실험별 대시보드는 역방향으로 제한된 페이지를 읽고 JVM point를 제외한 experiment preview를 복원한다. 실행이 짧아 원래 pipeline의 250ms progress tick 전에 끝난 경우 progress가 없을 수 있으며 summary 행 수는 그대로 보존한다. JVM point를 진행률 0으로 변환하지 않는다.

새 schema version과 JVM mode는 parameters에 보존되므로 활성/비활성 Run의 비교에서 조건 차이가 드러난다. 기존 Run은 version이 없으며 현재 catalog는 알려진 legacy 이름의 설명으로만 사용된다. 단위 자동 변환이나 값 재작성은 하지 않는다.

`./gradlew test`는 활성/비활성, 실패 종료와 수집 실패, 미지원 MXBean 값 생략, 최소 page limit에서의 preview 및 재개방, 실제 HTTP CLI를 통한 세 실험을 검증한다. `build/reports/observability/jvm-snapshot.json`은 20회 준비 후 100회 capture의 평균/최대 시간과 JSON payload 최대 바이트를 기록한다. 2 KiB 미만의 고정 sample shape를 검증한다(수집 단계 payload, 저장 envelope 제외). 이것은 publication/DB 대기나 전체 실행 slowdown의 보장이 아니다. summary의 captureNanos도 수집 호출만 포함하며 writer 배압 비용은 storage.enqueueWaitNanos에 포함된다.

경계 전용 모드는 새로운 작업 thread를 만들지 않으며 기존 writer 종료 순서를 따른다. 시작 전 executor 거부는 snapshot이 없고, 강제 프로세스 종료는 end가 없을 수 있다. 이 경우 기존 INTERRUPTED 복구가 적용된다. 원문 서버 로그는 수집하지 않으며 아래 명시적 Run lifecycle 로그를 지원한다.

## 주기적 관측

```bash
./gradlew bootRun --args='--lab.observation.jvm-enabled=true --lab.observation.jvm-interval-ms=1000 --lab.observation.max-periodic-samples=1000'
```

| 설정 | 기본값 | 허용 범위 |
| --- | --- | --- |
| jvm-enabled | false | true/false |
| jvm-interval-ms | 0 | 0(경계 전용) 또는 100–60000 ms |
| max-periodic-samples | 1000 | 1–10000 |

활성 Run마다 가상 sampler thread 최대 하나를 사용한다. 동시 Run 수는 기존 max-active-runs로 제한한다. 시작/종료 2점 외에 periodic point를 최대 max-periodic-samples개 추가하며, labels는 source=jvm.periodic, phase=periodic, scope=process다. 고정 지연 방식이므로 수집/저장 시간만큼 실제 표본 간격은 늘어난다. 예약 작업 queue나 catch-up 실행은 없다. 기존 bounded writer의 배압/재시도/실패 처리를 공유한다.

summary의 observation.jvm.periodicSamples와 observation.jvm.capReached(0/1)는 저장된 주기 표본 수와 수집 상한 도달을 나타낸다. 상한 이후에는 종료 표본만 추가하므로 이후 구간 전체를 관측했다고 해석하면 안 된다. mode=PERIODIC, intervalMs, maxPeriodicSamples는 metadata에 보존한다. 기존 대시보드 preview는 boundary/periodic point를 제외하며 /runs 원본과 export에는 모두 남는다.

종료 요청은 sampler 대기를 깨우고 진행 중인 게시가 끝나기를 기다린 후 end를 게시하고 writer를 drain한다. sampler 실패는 다음 실험 metric 게시 또는 실행 종료에서 Run 실패로 전파한다. 끝나지 않는 실험을 sampler가 강제 종료하지 않는다. 기존 storage.shutdown-timeout-ms 이내에 sampler가 정지하지 않으면 interrupt하고 Run을 실패 처리하며 정상 end를 보장하지 않는다. 인터럽트를 무시하는 JVM 호출을 강제로 종료할 수는 없다.

검증은 표본 상한·메타데이터·재개방, 배압 중 종료 순서와 추가 표본 미발행, 비동기 실패 전파와 잘못된 설정 거부를 포함한다. 각 sample은 기존 고정 크기 스냅샷이며 정확한 시간 간격이나 실행 중 peak 포착을 보장하지 않는다.

## 지표 의미와 집계

Schema v2는 RUN_CUMULATIVE / PROCESS_CUMULATIVE / RUN_MEAN / INTERVAL_RATE / RUN_RATE / INTERVAL_DELTA / OBSERVED_MAXIMUM / INSTANTANEOUS / RATIO / CONFIGURATION / FLAG / SPAN_DURATION을 명시한다. 누적 snapshot을 시간축으로 합산하거나 평균의 평균을 내지 않는다. 구간 rate는 실제 구간 길이가 필요하며 반올림된 rate에서 정확한 건수를 복구할 수 없다. GC 누적 시간은 process 전체 collection time이다. 기존 DB 값과 이름은 변경하지 않는다. 과거 Run의 schema version은 보존하며 catalog의 설명을 소급 계측 증거로 해석하지 않는다.

## 명시적 Trace — 로컬 span v1

```bash
./gradlew bootRun --args='--lab.trace.enabled=true --lab.trace.max-spans=1000'
```

기본값은 enabled=false, max-spans=1000이며 허용 범위는 1–10000이다. Run parameters에 mode=OFF/EXPLICIT, schemaVersion=1, maxSpans를 남긴다. 활성 실행은 EXPERIMENT 루트 span 하나를 예약하고 IO 비교에서는 IO_VIRTUAL_REQUEST / IO_REACTIVE_REQUEST 자식 span을 기록한다. 큐와 Excel은 현재 전체 실행만 관측한다. 내부 파싱·JDBC 단계나 분산 네트워크 trace, ThreadLocal 기반 자동 전파는 포함하지 않는다. 부모 ID를 직접 전달하며 자식 작업은 execute 반환 전에 완료되어야 한다.

source=trace.span인 MetricPoint를 기존 bounded writer로 저장한다. traceId는 Run ID, spanId는 Run 내 예약 순번, parentSpanId는 부모 순번(루트는 빈 문자열)이다. stage는 고정 enum, status는 SUCCEEDED/FAILED, traceSchemaVersion은 1이다. 예외 메시지·요청 내용·스택을 span에 넣지 않는다. 기존 Run 실패 이유 저장 정책과는 별개다. 시작 timestamp는 wall clock이고 trace.durationNanos는 monotonic clock의 inclusive 시간이다. 부모에는 자식 계측/게시 비용이 포함되므로 부모·자식 시간을 합산하지 않는다. 자체 writer 게시 비용은 제외하되 동시에 완료한 span의 게시 동기화 대기는 포함할 수 있다.

루트도 상한에 포함되며 예약 한도 이후에는 작업을 그대로 실행하고 trace.spans.dropped를 증가시킨다. summary의 trace.spans.recorded는 writer에 게시된 span 수다. 별도 span 목록이나 queue는 만들지 않는다. 종료 시 recorder를 닫고 기존 writer를 drain한다. 계측/저장 실패는 Run 실패로 전파한다. 프로세스 강제 종료는 미완료 span을 남기지 못할 수 있으며 기존 INTERRUPTED 복구를 따른다. 완료 순서로 저장하므로 자식이 루트보다 먼저 조회될 수 있다.

`GET /runs/{id}/traces?after=-1&limit=100`은 원본 metric 페이지에서 span을 골라 반환한다. 응답 형식은 metrics/nextAfter다. **빈 metrics여도 nextAfter가 있으면 계속 조회**한다. limit은 혼합 원본의 스캔 상한이다. `/runs/{id}/metrics`와 NDJSON export에도 span이 포함되며 기존 실험 대시보드 preview에서는 제외한다. 전용 trace UI는 아직 없다.

테스트는 예약 상한·작업 보존·실패 상태·게시 실패·비활성 모드·재개방·혼합 페이지 및 HTTP 선언적 실행의 부모 연결을 검증한다. `build/reports/observability/trace.json`은 1000회 빈 작업 계측과 JSON 직렬화의 총 시간 및 최대 payload 크기를 기록한다. 표준 UUID 입력의 point 크기가 1 KiB 미만인지 검증한다. DB envelope/인덱스·writer 대기·실제 실험 slowdown은 이 측정에 포함하지 않는다. 최대 10000개 설정에서 이 표준 point payload는 합계 10 MiB 미만이며 DB 파일 크기의 상한은 아니다. Trace를 켠 결과는 계측 비용이 포함된 별도 실험 조건으로 비교해야 한다.

## 선택적 JFR — BASIC_V1

```bash
./gradlew bootRun --args='--lab.jfr.enabled=true --lab.jfr.duration-seconds=60 --lab.jfr.max-bytes=16777216'
```

| 설정 | 기본값 | 허용 범위 |
| --- | --- | --- |
| lab.jfr.enabled | false | true/false |
| lab.jfr.duration-seconds | 60 | 1–3600초 |
| lab.jfr.max-bytes | 16777216 | 1–256 MiB |

Run마다 Recording 하나를 실행 직전 시작하고, 실행 및 JVM 관측 종료 뒤 정지·파일 저장·artifact 등록을 마친 후 Run을 완료한다. 성공뿐 아니라 실패한 실험도 녹화를 보존한다. 거부된 실행은 녹화를 시작하지 않는다. 기존 max-active-runs가 동시 녹화 수도 제한한다. 비활성 상태에서는 Recording과 디렉터리를 생성하지 않는다. 설정은 observation.jfr.* parameters에 보존된다.

BASIC_V1은 jdk.CPULoad(1초 주기), jdk.GarbageCollection, jdk.GCHeapSummary만 활성화하고 stack trace를 끈다. 표본은 **프로세스 전체** 값이며 다른 동시 실험·서버·DB 작업이 포함된다. 짧은 실험은 CPU 표본이 없을 수 있다. GCHeapSummary는 GC 전후 heap이고 peak 보장이 아니다. 실행 표본/할당 스택/virtual-thread pinning의 분석용 profile은 이 버전에 포함하지 않는다.

### 저장과 상한

파일은 lab.storage.root 아래 `jfr/jfr_<무작위 UUID>.jfr`에 저장한다. Run ID나 입력 경로를 파일명으로 사용하지 않는다. 8 KiB 복사 버퍼로 임시 파일에 스트리밍하며 max-bytes를 넘기기 전에 실패시켜 잘린 JFR을 등록하지 않는다. 파일을 검사하고 원자적으로 이름을 변경한 뒤 실제 바이트 수와 SHA-256을 기존 artifact metadata에 등록한다. `/runs/{id}/artifacts`, Run 상세 및 NDJSON export에서 참조를 확인한다. 참조 경로의 기준은 storage root다. 종료 Run은 [검증된 다운로드 API](run-evidence.md#jfr·run-로그-다운로드)로 파일을 받거나 서버의 해당 경로에서 직접 읽는다.

Recording.setMaxSize는 JVM repository의 **chunk 단위 보존 정책**이다. 엄격한 repository 디스크 한도가 아니며 max-bytes의 엄격한 검사는 최종 파일 복사에 적용된다. 상한으로 오래된 chunk가 사라질 수 있어 파일을 전체 실행 기록으로 해석하지 않는다. duration-seconds 후에는 녹화만 자동 정지하고 실험은 계속된다. observation.jfr.durationLimitReached=1은 마무리 시 이미 정지된 녹화를 관측했음을 뜻하며 파일이 실험 전체를 포함한다는 보장이 아니다. [JDK Recording API](https://docs.oracle.com/en/java/javase/16/docs/api/jdk.jfr/jdk/jfr/Recording.html)의 크기/기간 보존 계약을 따르며 저장소의 Java 25에서도 실제 파일로 검증한다.

자동 삭제 정책은 아직 없다. 누적 artifact 파일과 DB는 운영자가 함께 보관·정리해야 한다. 정상 종료는 recorder를 닫아 JVM repository 자원을 해제한다. 강제 종료 시 완성 artifact가 없거나 `.partial` 파일이 남을 수 있고 Run은 기존 INTERRUPTED 복구를 따른다. 시작 시 미완성 파일을 자동 삭제하지 않는다. 파일은 완성됐지만 DB 등록이 실패하면 Run을 FAILED로 기록하고 파일을 복구용으로 남긴다. 서버 오류 로그의 job_id와 artifact_id로 연결 대상을 확인하고 파일 크기/checksum을 계산해 기존 artifact 등록 API로 연결할 수 있다. JFR 정지·파일 I/O는 동기식이며 duration-seconds는 이 마무리 작업의 timeout이 아니다.

### 민감정보와 실패 정책

기본 JVM profile, 환경변수, 시스템 프로퍼티, JVM 인자, 파일/소켓 I/O, 예외 메시지 이벤트는 활성화하지 않는다. 다른 Recording과 설정이 합쳐질 수 있으므로 파일의 모든 이벤트를 한 번씩 읽어 허용된 세 종류와 stack 부재를 확인한다. 다른 이벤트나 stack이 있으면 임시 파일을 삭제하고 Run을 FAILED로 처리한다. JFR의 네이티브 metadata/상수 풀과 thread 정보까지 익명화하는 기능은 아니다. 외부 도구의 녹화와 함께 사용한 파일을 자동 정제한다고 간주하지 않는다.

수집·검증·등록 실패를 성공으로 숨기지 않는다. writer의 metric drain은 JFR 실패 이후에도 수행한다. JFR 파일은 재시도 가능한 metric queue 항목과 별개이며 DB 참조 등록 실패 시 파일을 재생성하지 않는다.

### 검증과 비용

JfrRecorderTest는 실제 녹화의 성공/실패·허용 이벤트·SHA-256·재개방·API/export 참조, 자동 정지, 비활성 모드, 복사 상한, 다른 녹화의 이벤트 거부·임시 파일 정리, 시작/등록 실패와 Recording 해제를 검증한다. LabCliIntegrationTest는 실제 서버에서 세 실험을 JVM sampling/Trace/JFR과 함께 실행한다.

`build/reports/observability/jfr-success.json`과 `jfr-failed.json`에 파일 바이트 수와 observation.jfr.lifecycleNanos를 기록한다. observation.jfr.bytes는 검증을 마쳐 최종 파일로 보존한 바이트 수이며 파일 생성 실패 시 0이다(DB 등록 실패 시에는 복구용 파일의 크기). lifecycleNanos는 디렉터리 준비·시작 및 정지·복사·검증·종료의 호출 시간이며 DB 등록 시간과 실행 중 JVM 계측 비용은 제외한다. 최초 JFR 초기화 비용과 후속 실행은 다를 수 있다. 이 수치는 전체 실행 slowdown이나 특정 성능 우위를 보장하지 않으며 활성화한 실행은 별도 측정 조건으로 비교한다.

## 선택적 Run 로그 — LIFECYCLE_V1

```bash
./gradlew bootRun --args='--lab.logs.enabled=true --lab.logs.max-events=100 --lab.logs.max-bytes=65536'
```

| 설정 | 기본값 | 허용 범위 |
| --- | --- | --- |
| lab.logs.enabled | false | true/false |
| lab.logs.max-events | 100 | 2–10000 |
| lab.logs.max-bytes | 65536 | 4096–1048576 bytes |

이번 버전의 수집 범위는 runner가 명시적으로 발생시키는 Run lifecycle 이벤트다. EXECUTION 시작·성공·실패·중단, JVM/JFR/metric 저장 실패와 EVIDENCE_FINISHED를 기록한다. 기본 OFF이며 Run parameters에 mode=LIFECYCLE_V1/OFF와 두 상한을 남긴다. 임의 SLF4J 메시지나 console 출력, worker별 원문 로그, GC 텍스트 로그의 수집기는 아니다. MDC/ThreadLocal에 의존하지 않고 Run별 session을 직접 전달하므로 동시 실행의 파일이 분리된다.

한 줄에 schemaVersion, timestampEpochMs, job_id, stage, event, reason, droppedEvents를 담은 UTF-8 NDJSON이다. stage/event/reason은 고정 enum만 허용하고 예외 메시지·스택·입력 행·환경변수·경로·자유 형식 메시지를 넣지 않는다. job_id는 최대 100문자이며 JSON으로 escape한다. 기존 Run failureMessage와 서버 오류 로그의 보존 정책은 별개다.

### 상한·실패·완료 경계

이벤트 목록이나 추가 queue/thread를 만들지 않고 동기식으로 스트리밍한다. 각 줄은 최대 1 KiB다. 이벤트/바이트 예산에서 마지막 이벤트 1개와 1 KiB를 미리 확보하고, 남은 예산을 초과한 중간 이벤트는 droppedEvents를 증가시킨다. 마지막 줄과 summary의 observation.logs.dropped에서 누락을 확인한다. 한도 안에서도 현재 lifecycle 단계가 적으면 파일은 수 줄뿐이다. 마지막 reason은 가장 최근 실패 원인이며 원인 전체를 대표하지 않는다.

metric writer drain 뒤 로그를 마무리하므로 STORAGE_FAILED도 기록할 수 있다. **EVIDENCE_FINISHED는 로그 artifact 등록과 Run의 DB 완료 이전**이다. reason=NONE이나 EXECUTION/SUCCEEDED가 Run의 최종 성공을 보장하지 않으며 최종 상태는 `/runs/{id}`에서 확인한다. 로그 쓰기 오류는 session에 보존했다가 finish에서 전파하고, 기존 metric drain을 마친 후 Run을 FAILED로 처리한다. 시작 시 파일을 열지 못하면 실험을 시작하지 않는다. 일반 Error가 실험에서 전파되어도 로그 마무리를 시도하며, 해당 Run의 기존 미완료 복구 정책은 유지한다.

파일은 storage root 아래 `logs/log_<UUID>.ndjson`에 저장한다. 임시 파일을 닫고 원자적으로 이름을 변경한 다음 실제 크기·SHA-256을 기존 artifact로 등록한다. 파일명에 사용자 입력을 넣지 않는다. `/runs/{id}/artifacts`, 상세 화면과 export는 참조를 제공하며 종료 Run의 관리 파일은 [다운로드 API](run-evidence.md#jfr·run-로그-다운로드)로 받을 수 있다. 중단/쓰기 실패 시 미완성 파일은 정리하고, 완성 후 DB 등록 실패 시 파일을 복구용으로 남긴다. 오류 로그의 job_id/artifact_id 및 파일 내용으로 Run을 확인하여 기존 artifact API로 복구할 수 있다. 강제 프로세스 종료에서는 `.partial`이 남을 수 있으며 자동 삭제하지 않는다.

파일별 상한은 전체 보관 용량의 상한이 아니다. 자동 보관 기간/삭제는 아직 없으므로 운영자가 artifact 참조와 파일을 함께 관리한다. 로그 I/O는 실험 실행 시간에 영향을 주며 저장 장치가 멈출 때 독립적인 I/O timeout은 제공하지 않는다.

### 검증과 비용

RunLogRecorderTest는 성공/실패/중단, 재개방·export 참조, checksum·민감 문자열 제외, 두 상한과 마지막 이벤트 보존, 비활성/잘못된 설정, 시작/쓰기/등록/metric drain 실패, 임시 파일 정리 및 동시 session 분리를 검증한다. 실제 HTTP CLI 테스트는 JVM/Trace/JFR/로그를 함께 활성화해 세 실험의 완료를 확인한다.

summary의 observation.logs.events/bytes는 성공한 쓰기 호출의 이벤트 수·바이트 수이며, 파일 생성 실패 시 삭제된 중간 데이터의 집계일 수 있다. captureNanos는 파일 준비·직렬화·쓰기·종료/rename 시간이며 DB 등록 비용은 제외한다. `build/reports/observability/run-logs.json`은 두 동시 session의 최대 파일 크기와 검증 읽기를 포함한 총 시간을 기록한다. 이 값은 전체 실험 slowdown의 보장이나 비활성 모드와의 성능 비교 결과가 아니다.
