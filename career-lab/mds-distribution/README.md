# mds-distribution-lab

FLO(드림어스컴퍼니) 글로벌 유통 시스템(Music Distribution System, MDS) 구축
경험 중 이력서·면접에서 가장 자주 다루는 두 가지 설계를 **재구현 가능한
최소 단위**로 재현한 포트폴리오 랩입니다. 이력서 문구를 코드와 테스트로
직접 증명하는 것이 목적입니다.

원본 사실은 `career-hub` 저장소의 `facts/projects/mds-global-distribution.md`,
설계 문서는 `design/mds-global-distribution.md`에 있습니다. 이 저장소는 그
문서들의 확정된 Problem → Decision → Impact만 골라 실제로 동작하는 코드로
옮긴 것입니다. `equity-system-lab`(지분율 시스템 재구현 랩)과 동일한
방법론·컨벤션을 따릅니다.

## 사실과 신규 설계의 구분

모든 클래스/메서드에 `[FACT 기반 재현]` 또는 `[NEW-DESIGN]`(또는 두 표기를
함께 — "사실 근거 원리 + NEW-DESIGN 구현")을 주석으로 남겼습니다.

- **[FACT 기반 재현]**: facts에서 확정된 문제·결정을 그대로 재현한 부분
  (예: 상태 플래그 기반 경량 락 + AOP 가드로 정산 중 메타데이터 수정 차단,
  TTL 기반 자동 복구, 메시지 ID DB 유니크 제약 기반 멱등 처리)
- **[NEW-DESIGN]**: facts에 "확인 필요"로 남아있던 구체적 수치(TTL 값,
  멱등 처리 스키마 등)나, 재현을 위해 새로 지어낸 스키마·도메인 이름 —
  **실제 FLO 값이 아닙니다.**

## 재현한 2가지 시나리오

| 시나리오 | Before(문제) | After(해결) | 테스트 |
|---|---|---|---|
| 정산 중 메타데이터 수정 차단 | 가드 없음 — 정산 중에도 수정이 그대로 반영 | 상태 플래그 + AOP 가드(`@SettlementGuarded`) + TTL 자동 복구 | `settlement/SettlementGuardTest` |
| 이벤트 기반 대량 처리의 멱등성 | (SQS 자체는 시뮬레이션 — 아래 "SQS를 시뮬레이션으로 대체한 이유" 참고) | 메시지 ID DB 유니크 제약(`processed_message` PK) 기반 멱등 처리 | `messaging/AlbumRegistrationIdempotencyTest` |

두 시나리오 모두 실제 FLO 코드가 아니라(퇴사 후 원본 코드 접근 불가), facts에
확정된 Problem/Decision/Impact를 근거로 처음부터 새로 설계·구현했습니다.
자세한 설계 배경은 `design/mds-global-distribution.md`(career-hub 저장소)를
참고하세요.

### 1. 정산 중 메타데이터 수정 차단 — 상태 플래그 기반 경량 락 + AOP

- `settlement.SettlementGuarded` — 정산 보호 대상 메서드에 붙이는 애노테이션
  (`type`, `idParam`으로 SpEL을 통해 파라미터에서 대상 ID를 추출)
- `settlement.SettlementLockAspect` — `@Before` 어드바이스로 락 상태를
  **읽기만** 한다. 락 획득/해제는 이 Aspect의 책임이 아니라 정산 배치의
  책임이다(관심사 분리).
- `settlement.SettlementBatchService` — 정산 배치 역할. `tryAcquire`를
  "체크 후 갱신" 두 단계가 아니라 단일 조건부 UPDATE(`UPDATE ... WHERE
  status='UNLOCKED' OR expires_at<now`)로 원자화해 race window를 없앴다.
- `settlement.AlbumMetadataService` — 같은 수정 로직을 가드 없는 메서드
  (`updateMetadataUnguarded`, Before/문제)와 가드가 붙은 메서드
  (`updateMetadataGuarded`, After/해결)로 나란히 두어, "가드 유무"만이
  유일한 변수임을 테스트로 대비시킨다(`equity-system-lab`의 Legacy/Safe
  대비 패턴과 동일한 의도).
- TTL(15분은 실제 FLO 값이 아니라 이 랩을 위해 새로 정한 값 — `design`
  8장 참고)이 지나면, 별도 복구 배치 없이 **다음 조회 시점에 지연 평가**로
  자동 UNLOCKED 취급된다(`SettlementLock.isActiveConsidering`). 테스트에서는
  짧은 TTL(2초)로 이를 Awaitility로 증명한다.

### 2. 이벤트 기반 대량 처리의 멱등 처리

- `messaging.IdempotentMessageProcessor` — 메시지 ID를 PK로 하는
  `processed_message` 테이블에 INSERT를 시도해 신규/중복을 가른다.
- `messaging.AlbumRegistrationListener` — 실제 `@SqsListener`를 흉내낸
  핸들러. `onMessage()`를 테스트(또는 향후 실제 SQS 어댑터)가 직접 호출한다.
- 순차 중복, **동시(concurrent) 중복**, 실패 후 재시도 세 가지 상황을 모두
  통합 테스트로 증명한다.

#### SQS를 시뮬레이션으로 대체한 이유

실 AWS 계정이 없어 진짜 SQS를 쓸 수 없다. LocalStack Testcontainers를
검토했지만, 이 랩이 실제로 증명하려는 것은 "메시지가 최소 한 번(at-least-once)
중복 전달될 수 있다"는 전제 하에서의 **멱등 처리 로직**이지 SQS 자체의 큐잉
동작이 아니다. LocalStack을 띄우면 인프라 복잡도만 늘고 시연 가치는 크지
않다고 판단해, `AlbumRegistrationListener.onMessage()`를 SQS 리스너와 동일한
시그니처로 두되 테스트가 직접(또는 동시에 여러 스레드에서) 호출해 "중복
전달"을 결정론적으로 재현하는 방식을 택했다. 재시도·DLQ 정책(design 5.3절)
자체는 인프라 레벨(SQS 리드라이브 정책) 책임이라 애플리케이션 코드로
재현할 성질이 아니므로 이 랩의 범위에서 제외했다.

## 실행 방법

```bash
export JAVA_HOME=/Users/hammac/Library/Java/JavaVirtualMachines/corretto-21.0.7/Contents/Home
./gradlew test
```

Docker(Testcontainers)가 필요합니다 — MySQL 컨테이너를 자동으로 띄우고
종료합니다. 전체 실행에 30초~1분 정도 걸립니다. `./gradlew clean test`로
두 번 연속 실행해 8개 테스트 전부 PASS하는 것을 확인했습니다(flaky 체크).

## 실행하며 실제로 발견한 것 (정직하게 남겨둠)

이 랩의 목적 자체가 "설계 문서의 주장이 실제로 맞는지 코드로 검증"이었기
때문에, 만들면서 부딪힌 예상 밖의 문제도 그대로 남깁니다.

### 1. `@Id`를 애플리케이션이 직접 채우면 `save()`가 INSERT 대신 UPDATE를 해버린다

`ProcessedMessage.messageId`는 `@GeneratedValue` 없이 애플리케이션이 직접
채우는 PK다. 처음엔 `JpaRepository.saveAndFlush()`로 "INSERT 시도 → 실패하면
중복"이라는 멱등 체크를 구현했는데, 테스트에서 **중복 메시지가 멱등 처리되지
않고 실제 앨범이 2건 등록**되는 결과가 나왔다.

원인은 Spring Data JPA의 `isNew()` 판정 방식이었다: ID가 `null`이 아니면
"새 엔티티가 아니다"로 판단해 `save()`가 `persist()`가 아니라 `merge()`를
호출한다. `merge()`는 "있으면 UPDATE, 없으면 INSERT"이기 때문에, 이미 DONE
처리된 메시지가 같은 ID로 다시 저장되면 유니크 제약 위반 예외 없이 **조용히
기존 행을 덮어써 버렸다** — 멱등성 체크 자체가 무력화되는 것이었다.

해결: `ProcessedMessage`가 `Persistable<String>`을 구현하고 `isNew()`가
항상 `true`를 반환하도록 고정했다. 이러면 `save()`가 항상 `persist()`(=INSERT
시도)를 호출하도록 강제되어, 이미 존재하는 `messageId`에는 DB가 실제로
유니크 제약 위반을 던진다 — 이것이 "메시지 ID에 DB 유니크 제약을 걸어
멱등성을 보장한다"는 설계가 성립하기 위한 전제였다는 걸 직접 겪고서야
알았다.

### 2. 인터페이스에 직접 선언한 `@Modifying` 쿼리 메서드는 기본 트랜잭션을 상속받지 않는다

`SettlementLockRepository.tryAcquire`/`release`, `ProcessedMessageRepository`의
커스텀 `@Query` 메서드들은 `JpaRepository`를 상속하지만, `SimpleJpaRepository`
클래스에 걸린 기본 `@Transactional(readOnly = true)`은 **상속받은 CRUD/파생
쿼리 메서드에만** 적용되고 인터페이스에 직접 선언한 커스텀 쿼리 메서드에는
적용되지 않는다. 처음엔 이걸 몰라서 `IdempotentMessageProcessor` 쪽에
`@Transactional(REQUIRES_NEW)`를 걸었는데, 같은 클래스 안에서
`this.tryInsertNew(...)`처럼 **자기 자신을 호출(self-invocation)**하면
스프링 프록시를 거치지 않아 그 애노테이션이 조용히 무시된다는 것까지 겹쳐,
`TransactionRequiredException`이 났다.

최종 해결: 트랜잭션 경계를 서비스/프로세서 레이어가 아니라 **리포지토리의
`@Modifying` 메서드 자체에 `@Transactional`을 직접 붙이는 것**으로
옮겼다. 이러면 그 메서드를 self-invocation으로 호출하든 외부에서 호출하든,
실제로 실행되는 것은 항상 스프링이 관리하는 리포지토리 빈의 프록시를 통한
호출이라 트랜잭션이 정상적으로 걸린다. self-invocation 함정을 서비스
레이어에서 우회하려 애쓰는 것보다, 애초에 트랜잭션 경계를 self-invocation이
발생하지 않는 지점(리포지토리 메서드)으로 옮기는 게 더 근본적인 해법이었다.

### 3. 동시 중복 메시지는 "유니크 제약 위반"이 아니라 "잠금 대기/데드락"으로 나타날 수도 있다

동시(멀티스레드) 중복 전달 테스트에서, 두 스레드가 같은 `messageId`로 거의
동시에 INSERT를 시도하면 항상 깔끔한
`DataIntegrityViolationException`(유니크 제약 위반)만 나는 게 아니라, MySQL의
잠금 대기 상황에 따라 다른 종류의 `DataAccessException` 계열 예외가 날 수도
있다는 걸 감안해, `IdempotentMessageProcessor.tryInsertNew`의 예외 처리를
`DataIntegrityViolationException` 대신 상위 타입인 `DataAccessException`으로
넓게 잡도록 설계했다. equity-system-lab의 Redis 락 레이스 테스트처럼, "동시에
같은 자원을 두 프로세스가 건드리면 정확히 어떤 예외가 나는지"는 타이밍에
따라 달라질 수 있다는 걸 감안해 보수적으로 처리하는 게 안전하다는 교훈은
동일했다.

### 4. Testcontainers를 여러 테스트 클래스에서 공유할 때의 함정 (equity-system-lab과 동일)

`@SpringBootTest` + 공유 `static` MySQL 컨테이너 패턴에서 여러 테스트
클래스를 연달아 돌리면 "Failed to obtain JDBC Connection"이 재현될 수 있다.
`@DirtiesContext(classMode = AFTER_CLASS)`로 테스트 클래스마다
ApplicationContext(및 HikariCP 풀)를 새로 만들도록 강제해 해결했다 —
`AbstractMySqlIntegrationTest`에 미리 적용해뒀다.

## 이 랩에서 하지 않은 것 (의도적 축소)

- REST 컨트롤러: 이 포트폴리오의 목적은 API 설계가 아니라 동시성 제어·이벤트
  파이프라인 설계를 테스트로 증명하는 것이라 생략했다(`equity-system-lab`과
  동일한 판단).
- 운영자 락 강제 해제 API(F-9), DLQ 실물 구현: design 문서에는 있지만 이
  랩이 증명해야 할 핵심(가드 동작, TTL 자동 복구, 멱등 처리)과는 거리가
  있어 시간 대비 가치가 낮다고 판단해 생략했다.
- DDEX 표준, 파트너/계약 도메인 전체: Situation 배경 설명일 뿐, 이 랩이
  시연할 두 가지 딥다이브와 직접 관련이 없어 생략했다.

## 구조

```
src/main/java/com/portfolio/mdslab/
├── domain/       Album, SettlementLock, ProcessedMessage 등 JPA 엔티티
├── repository/   Spring Data JPA 리포지토리 (원자적 조건부 UPDATE 포함)
├── settlement/   시나리오 1 — @SettlementGuarded, Aspect, 배치/서비스
└── messaging/    시나리오 2 — 멱등 처리, 리스너, 등록 서비스
```
