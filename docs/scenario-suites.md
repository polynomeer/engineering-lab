# 실무 시나리오 묶음(suite)

실무에서 겪은 상황 하나를 **suite 파일** 하나로 적고, `lab suite`로 그 안의 실험을 순서대로 자동 실행한다. 단일 실행, 반복 측정, 파라미터 스윕, 두 묶음 비교를 한 파일에 담는다. 실행 결과는 판정이 아니라 증거다. 각 단계의 대시보드 주소로 들어가 분포와 조건을 확인하고 해석은 [Finding](findings/README.md)에 남긴다.

## 실행

서버를 먼저 띄운다(`java -jar build/libs/engineering-lab-*.jar`). suite는 측정이 겹치지 않도록 한 번에 하나씩 실행하며, 반복 측정 중에는 다른 Run 접수가 409로 거절된다.

```bash
# 모든 suite를 검증만 하고 단계별 Run 수를 출력한다(서버 불필요)
./gradlew -q lab --args="suite $(ls experiments/suites/*.suite.yaml | tr '\n' ' ') --dry-run"
# 하나를 실행하고 전체 결과를 JSON으로 저장한다
./gradlew -q lab --args='suite experiments/suites/slow-consumer-backpressure.suite.yaml --server http://localhost:8080 --report build/suite-report.json'
```

- 실행 전에 모든 파일을 읽고 검증한다. 시나리오 정의·입력 파일 SHA-256, 스윕의 모든 점, 비교 대상 단계를 확인하며 하나라도 틀리면 아무것도 제출하지 않는다.
- stdout은 JSON lines다. 단계마다 한 줄(상태, 대시보드 주소, measure 중앙값·범위·변동 계수, 스윕 점별 결과, 비교 결과, 경고), suite마다 요약 한 줄, 마지막에 전체 요약 한 줄을 쓴다. `--report FILE`은 상황·질문까지 포함한 전체를 한 파일로 저장한다.
- 단계의 실제 상태가 `expect`와 다르거나 `assert`가 틀리면 그 단계와 suite가 실패로 표시되지만 다음 단계는 계속 실행한다. 제출 실패나 대기 시간 초과는 서버가 아직 그 작업을 돌리고 있을 수 있으므로 남은 단계를 모두 건너뛴다.
- 종료 코드: 0 모든 단계 통과, 1 실패한 단계 있음 또는 검증 오류, 2 대기 시간 초과·제출 실패로 중단.
- 단계별 대기 시간 기본값은 900초다. 단계의 `timeoutSeconds`나 명령의 `--timeout-seconds`로 바꾼다.
- CLI는 오프라인 catalog로 검증하므로 Career 시나리오는 suite에 넣을 수 없다.

GitHub에서는 [Scenario suites](../.github/workflows/scenario-suites.yml) 워크플로가 매주 일요일과 수동 실행 때 모든 suite를 빈 저장소에서 돌리고 보고서를 artifact로 올린다. CI runner의 수치는 기계가 달라 로컬 결과와 비교하지 않는다. suite가 계속 실행 가능한지 확인하는 용도다.

## 파일 형식

```yaml
schemaVersion: 1
suite:
  id: slow-consumer-backpressure          # 영문·숫자·점·밑줄·하이픈
  title: 소비자 지연으로 주문 이벤트 큐가 가득 참
  situation: 실제로 겪은 상황과 당시의 의견 차이
  question: 이 suite로 답하려는 질문 하나
steps:
  - id: incident                          # 단일 실행: scenario만
    scenario: ../concurrency/queue-contention/scenarios/order-events-slow-consumer.yaml
    repeat: 5                             # 반복 측정(1–50), warmup 0–10
    warmup: 1
  - id: more-consumers                    # 스윕: sweep이 있으면 점마다 repeat회(기본 3)
    scenario: ../concurrency/queue-contention/scenarios/order-events-slow-consumer.yaml
    sweep: {consumerThreads: [1, 2, 4]}
    repeat: 3
  - id: incident-vs-four-consumers        # 앞선 반복 측정이나 스윕 점(<step>.p<N>) 두 개를 비교
    compare: {baseline: incident, candidate: more-consumers.p2}
```

| 키 | 의미 |
| --- | --- |
| `scenario` | suite 파일 위치 기준 상대 경로의 시나리오 정의. 입력 파일은 시나리오 정의의 규칙을 따른다 |
| `expect` | `SUCCEEDED`(기본), `FAILED`, `ANY`. 스윕에서 `ANY`는 일부 점이 실패해도 되지만 **한 점 이상은 성공**해야 한다는 뜻이다(경계 찾기) |
| `assert` | 단일 실행의 summary counter 값 일치, 예: `{rows.inserted: 16000}` |
| `compare` | 판정하지 않고 measure별 중앙값 변화와 범위 겹침만 보고한다. 비교 단계는 항상 통과로 센다 |
| `timeoutSeconds` | 이 단계의 대기 시간(1–86400) |

suite 파일은 64 KiB, 단계 32개까지다. 알 수 없는 키와 중복 키는 거절한다.

## 포함된 suite

| suite | 상황 | 질문 | Run 수 |
| --- | --- | --- | ---: |
| [slow-consumer-backpressure](../experiments/suites/slow-consumer-backpressure.suite.yaml) | 결제 이벤트 소비자가 DB 지연으로 느려져 생산자 API까지 막힘 | 큐를 키울까, 소비자를 늘릴까 | 24 |
| [flash-sale-burst](../experiments/suites/flash-sale-burst.suite.yaml) | 선착순 이벤트로 요청 스레드가 급증, 처리 worker는 그대로 | 생산자를 늘리면 처리량이 느는가 | 13 |
| [slow-partner-api](../experiments/suites/slow-partner-api.suite.yaml) | 파트너 API 응답이 10ms → 200ms | 지연 앞에서 virtual thread·Reactor 차이가 의미 있는가 | 13 |
| [nightly-excel-slowdown](../experiments/suites/nightly-excel-slowdown.suite.yaml) | 야간 주문 Excel 적재가 배치 창을 넘김 | 파이프라인이 단일 스레드보다 빠른가, 무엇을 튜닝할까 | 26 |
| [bulk-upload-oom](../experiments/suites/bulk-upload-oom.suite.yaml) | 10만 행 업로드로 서버가 OOM 재시작 | 몇 MiB heap에서 끝나며 큐 상한이 heap을 좌우하는가 | 21 |
| [dirty-partner-file](../experiments/suites/dirty-partner-file.suite.yaml) | 오류 행이 섞인 거래처 파일로 실패·중복 저장 | 두 처리 방식이 같은 개수를 저장하고 오류를 남기는가 | 5 |

Excel 시나리오는 모두 `forkedHeapMiB`로 실행마다 별도 JVM과 DB를 쓴다. 반복 실행이 서버의 in-memory ingestion DB에 행을 쌓지 않고, heap 수치가 그 실행만의 값이 된다. fixture는 `experiments/pipeline/excel-ingestion/scenarios/fixtures/`의 `orders-20k.xlsx`, `orders-20k-dirty.xlsx`(5행마다 1행 오류, 4천 행), `orders-100k.xlsx`이며 다음 명령으로 만든 것이다. XLSX는 생성 시각이 들어가 다시 만들면 SHA-256이 바뀌므로, 다시 만들면 시나리오의 `input.sha256`도 갱신한다.

```bash
./gradlew -q generateTestXlsx --args='experiments/pipeline/excel-ingestion/scenarios/fixtures/orders-20k-dirty.xlsx 20000 5'
```

## 실행 예시 — 2026-10-26

아래는 개발 PC(macOS, Java 25, 빈 저장소, 다른 작업 없음)에서 6개 suite 102개 Run을 한 번 돌린 결과다. 4분 9초 걸렸고 모든 단계가 기대대로 끝났다. 같은 기계의 한 번 실행이며 다른 환경의 성능으로 일반화하지 않는다. 판정은 범위 겹침 기준이고 통계적 유의성 검정이 아니다.

| suite | 관측 |
| --- | --- |
| slow-consumer-backpressure | 큐 16→4096: 경과 시간 2,545→2,611 ms, 범위 겹침(차이 없음). 소비자 1→4: 2,545→610 ms(−76%), 처리량 393→1,639 items/s, 범위 분리 |
| flash-sale-burst | 생산자 2→32: 처리량 1,609→1,564 items/s(−2.8%)로 거의 그대로, 경과 시간 373→6,139 ms. 작업량이 늘어도 소비 용량에서 멈춘다 |
| slow-partner-api | 지연 10→200 ms: 처리량 83.8→4.9 requests/s(−94%). 순차 호출이라 지연이 그대로 처리량을 결정한다 |
| nightly-excel-slowdown | 단일 스레드 994 ms vs 파이프라인 965 ms, 범위 겹침. 저장 worker 1–4·batch 100/1000 조합도 912–991 ms로 비슷했다. in-memory H2에서는 저장이 병목이 아니어서 가설(파이프라인이 더 빠름)이 지지되지 않았다. 같은 suite의 이전 실행에서는 8% 차이로 범위가 분리됐으므로 반복 수를 늘려 다시 볼 대상이다 |
| bulk-upload-oom | 10만 행: 32 MiB는 3회 모두 `OutOfMemoryError`, 64 MiB부터 성공(heap 표본 최대 56 MiB). 64 MiB에서 큐 상한을 500에서 10만으로 풀어도 heap 52–58 MiB로 차이가 없었다. 소비가 생산을 따라가 큐가 차지 않았기 때문이며, 큐 상한의 효과를 보려면 저장을 느리게 만드는 조건이 필요하다 |
| dirty-partner-file | 파이프라인·단일 스레드 모두 생산 20,000, 저장 16,000, 오류 4,000으로 `assert` 통과. 반복 3회의 저장 행 수 변동 0 |

heap 표본은 20 ms 주기 MemoryMXBean 값이라 실제 peak의 하한이며, 몇몇 묶음에서 heap 변동 계수가 10%를 넘어 경고가 붙었다.
