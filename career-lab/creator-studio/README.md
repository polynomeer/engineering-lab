# creator-studio-lab

FLO(드림어스컴퍼니) **크리에이터 스튜디오** 프로젝트에서 겪었던 문제 중 확정된
사실만 골라 **재구현 가능한 최소 단위**로 재현한 포트폴리오 랩입니다. 이력서
문구를 코드와 테스트로 직접 증명하는 것이 목적입니다.

원본 사실은 `career-hub` 저장소의 `facts/projects/creator-studio.md`에, 구현
설계는 `design/creator-studio.md`에 있습니다. 이 저장소는 그 문서들의 확정된
Problem → Decision → Impact를 실제로 동작하는 코드로 옮긴 것입니다.

## 사실과 신규 설계의 구분

모든 클래스/메서드에 `[FACT 기반 재현]` 또는 `[NEW-DESIGN]` 표시를 남겼습니다.

- **[FACT 기반 재현]**: facts에서 확정된 문제·결정을 그대로 재현한 부분
  (예: access token 원문 Redis key 방식의 문제, 해시 key + 역인덱스 개선안,
  Producer/Creator 매핑 분리 원칙, Post-Commit 이벤트 구조)
- **[NEW-DESIGN]**: facts에 "확인 필요"로 남아있던 구체적 수치(TTL, 스레드풀
  크기, backoff 파라미터 등)나, 독립 프로젝트로 재구성하기 위해 새로 지어낸
  도메인 이름·스키마 — **실제 FLO 값이 아닙니다.**

특히 중요한 구분 하나: facts/projects/creator-studio.md 3번 섹션은 "문제를
식별하고 개선안을 제안하는 데까지만 진행했고, 실제 구현·배포는 하지
않았다(2026-09-24 사용자 확인)"고 명시합니다. **이 랩의 `session` 패키지가
그 미구현 설계를 처음으로 코드화한 것입니다.**

4번 섹션(Creator-Producer 매핑 장애)의 원인 분석은 facts에서도 "가설
단계이지 확정 아님"이라고 명시되어 있습니다. 이 랩의 `LegacyProducerCreationService`
는 그 가설 중 하나(②: Producer 생성이 기존 Creator의 producer_id를
덮어썼을 가능성)를 "만약 정말 그런 구조였다면 어떻게 재현되는가"로 코드화한
것이지, 실제 장애의 확정된 원인이 아닙니다.

## 재현한 시나리오

| 시나리오 | Before | After | 테스트 |
|---|---|---|---|
| 1. 세션 토큰 로테이션 (가장 중요) | access token 원문을 Redis key로 사용 — refresh 후에도 옛 token이 통과됨 | `sha256(token)` key + `sub:device` 역인덱스 + Lua 원자적 교체 — refresh 즉시 옛 token 거절 | `session/SessionRotationRaceConditionTest` |
| 2. Creator-Producer 매핑 무결성 | Producer 생성 로직이 기존 Creator의 producer_id를 무조건 덮어씀 | 가입(`CreatorOnboardingService`)과 매핑 변경(`ProducerMappingAdminService`)을 별개 진입점으로 분리, 콘텐츠 있으면 명시적 확인 없이는 차단, 변경 이력 기록 | `onboarding/CreatorProducerMappingIntegrationTest` |
| 3. Post-Commit 외부 연동 (선택) | — | `@TransactionalEventListener(AFTER_COMMIT)` — 커밋된 경우에만 Braze/Mixpanel 리스너 실행, 롤백 시엔 전혀 실행 안 됨 | `integration/PostCommitEventListenerTest` |

### 시나리오 1 상세 — 왜 가장 중요한가

facts 3번 섹션에서 본인이 직접 식별한 문제: "Studio는 access token 원문을
Redis의 key로 ... 저장해 세션처럼 사용했다. FE가 token을 refresh해 새 access
token을 받아도, 기존 access token의 만료 시각(exp)이 아직 남아 있고 Studio가
token rotation 사실을 알 방법이 없어 옛 access token으로도 Redis 세션이 계속
통과될 수 있는 빈틈이 있었다."

- `session.LegacySessionManager` — 이 문제를 그대로 재현합니다. refresh해도
  이전 access token의 세션 키를 지울 방법(역인덱스)이 없으므로 계속 유효합니다.
- `session.ImprovedSessionManager` — facts에 설계까지만 되어 있던 개선안
  (`sha256(access_token)` key + `sub+device_id` 역인덱스)을 실제로 구현합니다.
  refresh 시 Lua 스크립트로 "역인덱스에서 이전 해시 조회 → 이전 세션 삭제 →
  새 세션/역인덱스 등록"을 원자적으로 처리해, 동시 refresh 요청 간 경쟁 상태도
  방지합니다.
- 토큰 자체는 서명이 있는 실제 JWT가 아니라 무작위 opaque 문자열로
  단순화했습니다([NEW-DESIGN] — 증명 대상은 JWT 서명 검증이 아니라 Redis 세션
  키 구조이기 때문).

### 시나리오 2 상세

facts 4번 섹션은 "Creator-Producer 매핑 장애"의 증상과 당시 운영 대응(DML
직접 수정 대신 애플리케이션 정상 흐름으로 우회 복구)까지는 확정 사실이지만,
근본 원인은 두 가설(① 가입 여부 오판, ② Producer 생성 시 producer_id 덮어쓰기)
중 어느 쪽인지 확정하지 못했다고 명시합니다.

- `onboarding.LegacyProducerCreationService` — 가설 ②를 "그런 구조였다면"
  재현한 대조군입니다.
- `onboarding.CreatorOnboardingService.joinAsNewCreator` — 이미 Creator가
  있으면 즉시 거절해 가설 ①(재가입 오판)이 새 Producer를 만들 기회 자체를
  차단합니다. DB 유니크 제약(`uq_creator_member`)이 최후 방어선입니다.
- `onboarding.ProducerMappingAdminService.changeProducer` — Producer 매핑
  변경의 유일한 합법 경로. 콘텐츠가 있는 Producer는 `transferContent=true`
  명시 없이는 차단하고, 모든 변경은 `producer_mapping_history`에 기록됩니다.

## 실행 방법

```bash
export JAVA_HOME=/Users/hammac/Library/Java/JavaVirtualMachines/corretto-21.0.7/Contents/Home
./gradlew test
```

Docker(Testcontainers)가 필요합니다 — MySQL, Redis 컨테이너를 자동으로 띄우고
종료합니다. 전체 실행에 30~40초 정도 걸립니다. 10개 테스트 전부 통과를
2회 연속 확인했습니다.

## 실행하며 실제로 확인한 것

- **Spring Boot 3.3.4는 JDK 25 기본 `gradle`과 충돌**합니다 — Gradle 8.10과
  JDK 25 조합에서 빌드가 실패해, JDK 21(Corretto)로 `JAVA_HOME`을 고정해야
  했습니다. equity-system-lab에서 먼저 겪었던 문제라 이번엔 처음부터
  피해갔습니다.
- **`@TestConfiguration`을 테스트 클래스 안에 중첩해두는 것만으로는 자동
  주입되지 않았습니다.** `@SpringBootTest(classes = ...)`처럼 설정 클래스를
  명시하면 Spring Boot가 중첩 `@TestConfiguration`을 자동으로 추가 설정으로
  인식하지 못하는 경우가 있어, `@Import(RecordingClientsConfig.class)`를
  테스트 클래스에 명시적으로 붙여서 해결했습니다
  (`integration/PostCommitEventListenerTest`).
- **Testcontainers MySQL 컨테이너는 `AbstractMySqlIntegrationTest`의 여러
  서브클래스가 공유**하지만(재시작 비용 절감), `@DirtiesContext(classMode =
  AFTER_CLASS)`로 Spring ApplicationContext(및 HikariCP 풀)는 테스트 클래스마다
  새로 만들도록 강제했습니다 — equity-system-lab README에서 미리 경고한
  "Failed to obtain JDBC Connection" 문제를 이번엔 처음부터 피해간 것입니다.
- **`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`의 실행 시점 검증은
  Awaitility로만 가능**합니다 — 비동기 실행이라 테스트 스레드에서 곧바로
  단언(assert)하면 항상 실패하므로, "커밋되면 결국 호출된다"와 "롤백되면
  충분한 시간이 지나도 호출되지 않는다"를 각각 `await()`로 확인했습니다.

## 이 랩이 다루지 않는 것 (범위 밖)

- FLO 계정/Character 체계 — 이 프로젝트는 독립 프로젝트이므로 자체 `member`
  엔티티로 대체했습니다([NEW-DESIGN]).
- KMS 봉투암호화 — facts에 "KMS로 암호화한 대상 필드"가 확인 필요로
  남아있었고, design 8절에서 `member.email/phone/real_name`을 대상으로
  새로 정했지만, 이 랩은 실제 AWS KMS를 연동하지 않고 평문 컬럼으로
  단순화했습니다. 이 랩이 증명하려는 핵심은 세션 구조와 매핑 무결성이지
  암호화 구현이 아니기 때문입니다.
- 감사 로그(MDC/ELK), Episode/Clip CRUD, refresh token family 기반 탈취
  탐지 고도화 등 design 문서의 나머지 항목 — 시간 관계상 이번 랩에서는
  구현하지 않았습니다.

## 구조

```
src/main/java/com/portfolio/creatorlab/
├── CreatorLabApplication.java
├── domain/            Member, Producer, Creator, Program, ProducerMappingHistory (JPA 엔티티)
├── repository/        Spring Data JPA 리포지토리
├── session/           시나리오 1 — LegacySessionManager / ImprovedSessionManager
├── onboarding/        시나리오 2 — CreatorOnboardingService / ProducerMappingAdminService / LegacyProducerCreationService
└── integration/       시나리오 3(선택) — ContentChangedEvent, Braze/MixpanelEventListener, ProgramCreationService
```
