# mcp-platform-lab

FLO(드림어스컴퍼니) "음원 콘텐츠 플랫폼(MCP) 전면 개편" 경험을 **재구현 가능한 최소
단위**로 재현한 포트폴리오 랩입니다. 이력서 문구를 코드와 실제 테스트 실행으로
직접 증명하는 것이 목적입니다.

원본 사실은 `career-hub` 저장소의 `facts/projects/mcp-platform-revamp.md`와
`design/mcp-platform-revamp.md`에 있습니다. 이 저장소는 그 문서들의 확정된
Problem → Decision → Impact만 골라 실제로 동작하는 코드로 옮긴 것입니다. 같은
접근을 먼저 적용해 본 `equity-system-lab`(지분율 시스템 재구현 랩)의 교훈도
그대로 반영했습니다.

## 사실과 신규 설계의 구분

모든 클래스/메서드에 `[FACT 기반 재현]` 또는 `[NEW-DESIGN]` 주석을 남겼습니다.

- **[FACT 기반 재현]**: facts에서 확정된 문제·결정을 그대로 재현한 부분
  (계약 코드 채번의 다중 인스턴스 range allocation 충돌 → 두 가지 원자적 해법,
  ArchUnit 레이어 의존성 규칙, Bean Validation → DTO → 도메인 규칙 3단계
  검증 파이프라인)
- **[NEW-DESIGN]**: facts에 "확인 필요"로 남아있던 구체값(block_size,
  계약 코드 포맷 등)이나, 재현을 위해 새로 지은 스키마·도메인 이름·테스트
  전략 — **실제 FLO 값이 아닙니다.**

facts 문서 하단 "신규 후보 사실"(API 재설계로 인한 "약 1.5MM 절감",
`license_sqs` 소속 SQS 파이프라인)은 프로젝트 귀속·근거 자체가 불명확하다고
facts가 스스로 밝히고 있어 이 랩에서는 사실로 인용하지 않았습니다.

## 재현한 시나리오

| 시나리오 | Before | After | 테스트 |
|---|---|---|---|
| 1. 계약 코드 채번 동시성 (핵심) | SELECT로 읽고 → 애플리케이션에서 계산 → 별도 UPDATE (원자적이지 않음) | `UPDATE ... SET value = LAST_INSERT_ID(value + N)` 단일 원자적 UPDATE / `SELECT ... FOR UPDATE` 대안 | `sequence/NonAtomicAllocatorConcurrencyTest`, `sequence/AtomicUpdateAllocatorConcurrencyTest`, `sequence/SelectForUpdateAllocatorConcurrencyTest` |
| 2. ArchUnit 아키텍처 가드레일 | — | api→infrastructure 직접 호출 금지, domain→spring-web 의존 금지, 레이어 의존성 규칙 | `archrule/MainCodeArchitectureTest`(통과), `archrule/ArchitectureViolationDemoTest`(위반 시 실패 증명) |
| 3. 검증 파이프라인 (선택) | — | Bean Validation → DTO 매핑 → 도메인 규칙 검증 3단계 | `validation/ValidationPipelineTest` |

### 시나리오 1 — 계약 코드 채번 동시성 (가장 중요)

facts "4. 시퀀스 구조 개선 > 다중 인스턴스에서의 범위 충돌 가능성"에 2026-09-24·
2026-09-27 두 차례 정정된 내용을 그대로 재현했습니다.

- **Before**: `NonAtomicContractCodeAllocator` — SELECT로 현재 값을 읽고, 애플리케이션
  메모리에서 range를 계산한 뒤, 별도 UPDATE로 반영합니다. 이 세 단계가 하나의
  원자적 연산으로 묶여 있지 않아 여러 인스턴스가 동시에 호출하면 겹치는 범위를
  받을 수 있습니다.
- **After**: `AtomicUpdateContractCodeAllocator` — facts "올바른 개선방안" 2번
  (`UPDATE ... SET value = LAST_INSERT_ID(value + N)`)을 구현했습니다. 단일
  UPDATE 문 자체가 InnoDB 행 잠금으로 직렬화되므로 인스턴스 수와 무관하게
  안전합니다. 실제 애플리케이션 배선(`ContractService`)에서는 이 구현을
  `@Primary`로 사용합니다 — design 문서 7장이 정리한 대로 "성능이 아니라 코드
  단순성" 때문입니다.
- **대안**: `SelectForUpdateContractCodeAllocator` — facts "올바른 개선방안" 1번
  (`SELECT ... FOR UPDATE`)을 구현했습니다. facts의 회고("SELECT FOR UPDATE도
  실제로는 느리지 않았다")를 반영해 성능이 아닌 대안으로 나란히 두었습니다.

동시성 검증은 `ExecutorService` + `CountDownLatch`로 10개 스레드가 동시에
"1000개씩 범위 확보"를 호출하는 **실제 멀티스레드 테스트**입니다(CLAUDE.md
지침: 진짜 동시 DB 쓰기 검증에는 실제 멀티스레드가 필요). Before 쪽은 타이밍
운에 맡기지 않기 위해 SELECT 이후 UPDATE 이전에 50ms 인위적 지연을 두어
경쟁 구간을 결정론적으로 넓혔습니다 — 이는 design 문서 2.2절에 이미 명시된
재현 전략("인위적 지연 재현 후 측정")입니다.

### 시나리오 2 — ArchUnit 아키텍처 가드레일

`archrule/LayerArchitectureRules`에 정의한 규칙 3개(컨트롤러의 인프라 직접
호출 금지, 도메인의 Spring Web 의존 금지, 레이어 의존성)를 **완전히 같은
`ArchRule` 인스턴스**로 두 번 검증합니다.

- `MainCodeArchitectureTest` — 실제 프로덕션 코드(`com.portfolio.mcplab.contract`)에
  대해 검증 → 통과.
- `ArchitectureViolationDemoTest` — 일부러 규칙을 위반하도록 만든
  `badexample` 패키지(테스트 전용, 실제 애플리케이션에서 쓰이지 않음)에 대해
  같은 규칙을 검증 → `AssertionError`가 던져짐을 `assertThrows`로 확인.

무작정 실패하는 테스트를 커밋해두면 빌드가 항상 깨지므로, "위반 시 예외가
던져지는 것"을 검증하는 통과 테스트로 표현했습니다.

### 시나리오 3 — 검증 파이프라인 (선택, 구현함)

`ContractCreateRequest`(Bean Validation) → `ContractService`의 수동 매핑
(MapStruct 대신 단순화, README에 명시) → `Contract.create()`의 도메인 규칙
(만료일이 시작일보다 앞설 수 없음)까지 3단계를 분리했습니다. Bean Validation만
통과하고 도메인 규칙에서 걸리는 케이스를 `ValidationPipelineTest`에서
증명합니다.

## 재현 범위에서 제외한 것

design/mcp-platform-revamp.md는 앨범/트랙/계약/지분율 전체 도메인과
MyBatis(변경)+QueryDSL(조회) 이원화, Shadow Release까지 포괄하지만, 이 랩은
과제 지시에 따라 **시나리오 1(채번 동시성)과 시나리오 2(ArchUnit)를 가장
정확히 증명하는 것**을 목표로 범위를 좁혔습니다. Contract 도메인은 채번·
검증·레이어 규칙을 보여줄 최소한만 두었고, 영속성은 JdbcTemplate으로
단순화했습니다(MyBatis/QueryDSL 이원화, 앨범/트랙/지분율 전체 모델, Shadow
Release, RabbitMQ 이벤트 발행은 이 랩에 없습니다).

## 실행 방법

```bash
export JAVA_HOME=/Users/hammac/Library/Java/JavaVirtualMachines/corretto-21.0.7/Contents/Home
./gradlew test
```

Docker(Testcontainers)가 필요합니다 — MySQL 컨테이너를 자동으로 띄우고
종료합니다. 전체 실행에 30~40초 정도 걸립니다(ArchUnit·검증 파이프라인
테스트는 DB 없이 즉시 실행됩니다).

이 macOS 환경의 기본 `gradle`은 JDK 25로 동작해 Gradle 8.10과 호환 문제가
있으므로, 위처럼 JDK 21(Corretto)을 `JAVA_HOME`으로 지정한 뒤 `./gradlew`
(wrapper)로 실행해야 합니다.

### 실행 결과 (2026-09-29, 로컬)

`./gradlew clean test`를 연속 2회 실행해 모두 통과를 확인했습니다(flaky 체크).

```
16 tests completed, 0 failed  (2회 연속)
```

- `archrule.MainCodeArchitectureTest` — 3개 규칙 모두 통과
- `archrule.ArchitectureViolationDemoTest` — 3개 규칙 모두 badexample에서 위반(AssertionError) 확인
- `sequence.NonAtomicAllocatorConcurrencyTest` — 10스레드 동시 채번 시 겹치는 범위 발생 확인(버그 재현)
- `sequence.AtomicUpdateAllocatorConcurrencyTest` — 2회 반복 모두 겹침 0건, 빈틈 없는 커버리지 확인
- `sequence.SelectForUpdateAllocatorConcurrencyTest` — 2회 반복 모두 겹침 0건 확인
- `validation.ValidationPipelineTest` — 5개 테스트 모두 통과

## 실행하며 실제로 발견한 것 (정직하게 남겨둠)

- **Non-atomic 버전의 버그 재현은 인위적 지연 없이는 사실상 재현되지
  않았을 것입니다.** 10개 스레드가 동시에 출발해도 SELECT와 UPDATE 사이의
  실제 지연이 거의 없으면(로컬 MySQL, 같은 머신) 겹침이 우연히 발생하지 않을
  수도 있습니다. design 문서가 2.2절에서 이미 "인위적 지연 재현 후 측정"을
  명시해 둔 이유를 실제로 체감했습니다 — 순수 타이밍에 맡기는 대신 50ms 지연을
  코드에 넣어 매 실행마다 결정론적으로 재현되게 했습니다.
- **`LAST_INSERT_ID()`는 커넥션(세션) 상태라 트랜잭션 경계가 중요했습니다.**
  `AtomicUpdateContractCodeAllocator`의 UPDATE와 `SELECT LAST_INSERT_ID()`가
  같은 커넥션에서 실행되지 않으면 엉뚱한 값을 읽습니다. `@Transactional
  (propagation = REQUIRES_NEW)`로 감싸 Spring의 트랜잭션 동기화가 두 JDBC
  호출에 같은 커넥션을 재사용하도록 강제했습니다.
- **ArchUnit `layeredArchitecture()`의 `mayOnlyBeAccessedByLayers()`는 인자
  없이 호출하면 `IllegalArgumentException`을 던집니다.** "아무도 접근할 수
  없다"를 표현하려면 `mayNotBeAccessedByAnyLayer()`를 써야 합니다 — design
  문서의 예시 코드(`mayOnlyBeAccessedByLayers()`, 인자 없음)를 그대로 옮기면
  런타임에 깨진다는 것을 이 랩에서 처음 확인했습니다.
- **ArchUnit이 던지는 예외는 `ArchAssertionError`가 아니라 일반
  `AssertionError`입니다**(사용 중인 archunit-junit5 1.3.0 기준). 처음에는
  전용 예외 타입이 있을 것이라 가정하고 작성했다가 컴파일 에러로 발견했습니다.
- **`ContractCodeAllocator` 구현체 3개를 모두 스프링 빈으로 등록하면
  `ContractService`의 인터페이스 주입이 모호해집니다.** `AtomicUpdate...`를
  `@Primary`로 지정해 실제 배선에서는 이 구현(design 7장의 최종 선택)이
  쓰이게 하고, 각 동시성 테스트는 구체 클래스 타입으로 직접 주입받아 세
  구현을 독립적으로 비교했습니다.
- equity-system-lab에서 이미 겪은 함정(Testcontainers 공유 시 컨텍스트 캐싱
  문제, MySQL URL 파라미터)은 `AbstractMySqlIntegrationTest`에 그대로
  반영해 이번에는 처음부터 재현되지 않았습니다.

## 구조

```
src/main/java/com/portfolio/mcplab/
├── McpLabApplication.java
├── contract/
│   ├── api/                 ContractController, DTO
│   ├── application/         ContractService (트랜잭션 경계)
│   ├── domain/               Contract(Aggregate), 도메인 규칙
│   └── infrastructure/       ContractRepository (JdbcTemplate)
└── sequence/                 시나리오 1 — Before/After/대안 3개 채번 구현체

src/test/java/com/portfolio/mcplab/
├── AbstractMySqlIntegrationTest.java   Testcontainers 공유 베이스
├── sequence/                            시나리오 1 동시성 테스트 + 하네스
├── archrule/                            시나리오 2 — 규칙 정의 + 통과/위반 테스트
├── badexample/                          시나리오 2 전용 — 일부러 위반하는 데모 클래스
└── validation/                          시나리오 3 — 검증 파이프라인 테스트
```

## 다음에 추가하면 좋을 것

- 시나리오 3을 API 계층(`@RestControllerAdvice`로 Bean Validation 실패 400,
  도메인 예외 422 매핑)까지 확장
- k6/JMeter로 design 문서 2.2절의 p95 응답시간 목표 검증(이 랩은 API 재설계
  자체는 다루지 않음)
- MyBatis(변경)+QueryDSL(조회) 이원화, Shadow Release 비교 로직(design 6-(c))
