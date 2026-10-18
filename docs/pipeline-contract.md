# Excel / Equity 파이프라인 공통 계약 (P5)

기준: 2026-10-18. [패턴 설계](pattern-platform-design.md)와 [측정 계약](measurement-contract.md)을 따른다.
공통 성질은 입력→검증→변환→청크 저장이라는 책임 경계, 작업량 상한, 성공/실패의 명시적 전달이다.
동일한 처리 엔진이나 동일한 트랜잭션 정책을 뜻하지 않는다.

## 사례별 보존 계약

| 책임 | Excel ingestion | Equity registration |
| --- | --- | --- |
| 입력 | XLSX 첫 sheet 스트리밍, 행 번호와 원본 값 보존 | 호출자가 이미 만든 `List<RawEquityRow>`; 입력 전체는 메모리에 존재 |
| 검증·매핑 | 검증 실패를 행별 dead-letter로 전송, 유효 행 매핑 | `RowProcessor`가 검증·매핑, 오류 발생 시 해당 청크 실패 |
| 배압 | raw/mapped bounded queue; 생산자는 빈 공간을 기다림 | 제출된 청크와 Future 수를 `maxInFlight`로 제한; 완료를 회수해야 추가 제출 |
| 실행 | transform/insert worker pool, 단일 스레드 baseline | 순차 baseline 또는 fixed thread pool과 completion service |
| 배치 상한 | insert worker당 `chunkSize`행, 단일 스레드도 같은 상한 | 순차 1000행, 병렬 청크당 요청 `chunkSize`행 |
| flush | 크기·첫 행 이후 경과 시간·정상 종료 신호; 단일 스레드 시간 검사는 입력 callback에서 수행 | 크기·입력 끝의 부분 배치; 시간 기반 flush 없음 |
| 저장/트랜잭션 | REQUIRES_NEW batch transaction, 성공 후 개수/latency 집계 | 기존 `EquityShareInsertDao`의 동기 JDBC batch; 별도의 전체 등록 transaction 없음 |
| 재시도 | rollback 성공이 확인된 transient callback 실패만 bounded exponential backoff | 자동 재시도 없음; DAO/worker가 받은 오류를 전파 |
| commit 불확실성 | commit/rollback 실패는 재실행하지 않음 | 실패한 batch를 자동 재실행하지 않음; 이미 반영된 부분은 호출자/격리 실험에서 확인 |
| 정상 종료 | queue drain, 부분 배치 flush, 관측/오류 기록 후 종료 | 입력 끝까지 제출한 Future 완료를 모두 회수; 부분 청크 저장 |
| 실패/중단 | channel failure 전파, 기존 lifecycle/timeout·오류 증거 보존 | pending 취소, shutdownNow 및 executor close; InterruptedException이면 호출자 interrupt 복원 |
| 관측 | queue depth/wait, retries, 실패 행, 단계 latency, 처리량 등 | maxInFlightChunks, admission waits/nanos, insertedRows; 최종 drain은 admission wait에 미포함 |

Equity의 invalid row는 Excel의 dead-letter 계약으로 자동 변환하지 않는다. Equity에는 행별 DLQ와
bounded retry가 구현되어 있지 않으며, 공통화가 이를 새로 제공한다고 주장하지 않는다.
현재 Equity 취소는 cooperative이며 강제 종료 시간 상한을 제공하지 않는다. Excel의 종료 상한도
외부 JDBC의 협조·timeout 한계와 함께 해석한다. P5는 이 차이를 숨기거나 동작을 바꾸지 않는다.

Excel의 단계 queue 메모리는 각 capacity에 제한되고, insert 배치 보관량은 대략
`insertWorkers × chunkSize`행이다(활성 stage 로컬 값·파서/DB 버퍼는 별도).
Equity의 추가 mapped 행은 최대 `min(workers, maxInFlight) × chunkSize`, pending Future는
`maxInFlight`개다. raw 입력 List는 O(전체 입력)이므로 전체 등록을 bounded-memory streaming으로
표현하면 안 된다. Queue capacity와 in-flight window는 서로 다른 제약이다.

## 최소 코드 추출: BoundedBatch

`career-lab/pipeline-support`는 Java 21, 런타임 외부 의존성이 없는 `java-library`다.
`com.pnomeer.lab.pipeline.BoundedBatch<T>` 하나만 제공한다. Boot/JDBC/worker wire 라이브러리를
참조하지 않는다. 루트 Java 25는 Gradle composite dependency로, Equity Java 21은 Career
project dependency로 **같은 소스**를 사용한다. 기존 두 wrapper와 Boot 버전은 유지한다.
새 라이브러리는 infrastructure 모듈이며 실행 가능한 실험/대시보드 모듈을 추가하지 않는다.

- 생성 시 양수 capacity. `add`는 capacity 초과를 즉시 거부한다. 입력/도메인 검증은 소유자 책임이다.
- single-owner/thread-confined. worker마다 독립 배치를 사용한다. 내부 스레드나 queue를 만들지 않는다.
- `isFull`, `isEmpty`, `size`로 소유자가 기존 flush 시점을 결정한다. 타이머·재시도·종료 정책은 없다.
- `flushTo`는 비어 있으면 sink를 호출하지 않고 0을 반환한다.
- 동기 sink에 복사 없는 읽기 전용 List view를 빌려준다. sink는 view를 보관하거나 비동기로 사용하지 않는다.
- sink 정상 반환 뒤에만 rows를 비우고 개수를 반환한다. 청크 내부 순서는 보존한다.
- checked/runtime 예외와 Error는 변환하지 않는다. 실패 시 rows를 보존하지만 **자동 재시도하지 않는다**.
  보존됐다는 이유로 실패한 DB write를 다시 호출하면 안 된다. commit 결과 판정은 기존 adapter 책임이다.
- sink 실행 중 add/재귀 flush를 거부한다. 정상 반환 후 view는 비워지므로 보관이 필요하면 sink 안에서
  명시적으로 제한된 copy를 만든다. 운영 JDBC adapter는 동기 소비이므로 copy가 필요 없다.

루트의 단일/병렬 Excel 배치와 Equity의 순차/병렬 배치가 사용한다. Excel의 transaction과
metrics 갱신은 같은 flush callback 안에 유지한다. 성공한 DB write 이후 오류가 발생할 경우에도
기존처럼 상위 lifecycle로 전파하며 자동 재실행하지 않는다. SQL·DAO·parser·업무 규칙은 이동하지 않았다.
Equity 순차 경로는 배치마다 ArrayList를 재할당하던 대신 같은 상한의 버퍼를 비워 재사용한다.
이 변경을 성능 개선 수치로 주장하지 않는다.

## 확장과 변경 제한

새 사례는 이 batch primitive가 맞을 때만 사용한다. 스트리밍 입력/배압/재시도/DLQ/중단 계약을
사례별로 선언하고 별도 테스트한다. bounded queue를 in-flight limiter로 바꾸거나 공통 Spring
transaction manager로 묶는 것은 P5의 범위가 아니다. 현재 처리 결과/실패 의미를 바꾸는 정책은
별도 설계·시나리오·측정 방법 버전 검토가 필요하다.

실험 ID, scenario canonical/hash, Run metadata·측정 계약·export schema는 유지한다. 재빌드된
Equity worker jar에는 라이브러리가 포함되므로 artifact SHA-256이 달라진다. 기존 운영 image를
자동 교체하지 않으며, 향후 배포 시 기존 manifest/image 갱신 절차를 따른다.

## 검증

1. `./scripts/career-lab.sh test`: 라이브러리·다섯 Career 모듈·worker-support 전체 테스트.
2. `JAVA_HOME="$(/usr/libexec/java_home -v 25)" ./gradlew test`: 루트 전체와 composite library 테스트.
   두 wrapper가 같은 소스의 build 디렉터리를 사용하므로 순서대로 실행한다.
3. `BoundedBatchTest`: 상한/부분 batch/입력 순서/빈 flush/실패 보존/중단/읽기 전용·재진입 거부.
4. `BoundedRegistrationTest`: 순차 1000/1000/3 tail, 병렬 in-flight 상한·admission wait,
   검증/DAO 실패 무재시도, 중단 시 취소·interrupt 복원.
5. 기존 Excel `PipelineRunnerIntegrationTest`, `PipelineLifecycleIntegrationTest`, `BoundedChannelTest`,
   `DeadLetterIntegrationTest`, `RetryingBatchTransactionTest`: 크기·시간·종료 flush와 행 오류,
   drain/실패, rollback 기반 retry 및 불확실 commit/rollback 비재생.
6. 기존 `PipelinePerformanceTest`: 128 MiB fork, 동일 입력·5400 성공/600 오류·bounded preview,
   합성 저장 지연 조건의 순차/병렬 비교. Equity `RegistrationPipelineBenchmarkTest`는
   MySQL 동일 5000행 결과와 합성 검증 지연의 병렬 효과를 검증한다. 실제 업무 성능 주장이 아니다.

Career 실제 worker 통합 테스트는 재빌드된 worker jar를 사용한다. 운영 배포 갱신과 전체 모듈
운영 인수는 P7에서 수행한다.
