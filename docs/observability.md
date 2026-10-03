# 관측 schema와 JVM 스냅샷

5단계 첫 작업은 공통 단위 catalog와 선택적 JVM 시작/종료 관측이다. 주기적 JVM sampling도 선택적으로 지원한다. 명시적 trace, JFR/log 수집, 외부 인프라는 후속 작업이다.

## Metric catalog v2

`GET /metrics/schema`는 이름·단위·범위(run/process)·semantics·aggregation을 제공한다. 비교 API도 같은 catalog를 쓴다. ms/ns, bytes/MiB, percent, rate를 구분하며 알 수 없는 이름은 unknown이다. 기존 MetricPoint와 DB schema는 변경하지 않는다. legacy counters/gauges는 저장 컨테이너일 뿐 의미론적 counter/gauge 보장은 아니다(예: throughput rate가 counters에 존재).

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

경계 전용 모드는 새로운 작업 thread를 만들지 않으며 기존 writer 종료 순서를 따른다. 시작 전 executor 거부는 snapshot이 없고, 강제 프로세스 종료는 end가 없을 수 있다. 이 경우 기존 INTERRUPTED 복구가 적용된다. Trace/JFR/log는 아직 수집하지 않는다.

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

Schema v2는 RUN_CUMULATIVE / PROCESS_CUMULATIVE / RUN_MEAN / INTERVAL_RATE / RUN_RATE / INTERVAL_DELTA / OBSERVED_MAXIMUM / INSTANTANEOUS / RATIO / CONFIGURATION / FLAG를 명시한다. 누적 snapshot을 시간축으로 합산하거나 평균의 평균을 내지 않는다. 구간 rate는 실제 구간 길이가 필요하며 반올림된 rate에서 정확한 건수를 복구할 수 없다. GC 누적 시간은 process 전체 collection time이다. 기존 DB 값과 이름은 변경하지 않는다. 과거 Run의 schema version은 보존하며 catalog의 설명을 소급 계측 증거로 해석하지 않는다.
