# equity-system-lab

FLO(드림어스컴퍼니) 지분율 시스템 트랜잭션 안정성·동시성 개선 경험을
**재구현 가능한 최소 단위**로 재현한 포트폴리오 랩입니다. 이력서 문구를
코드와 벤치마크로 직접 증명하는 것이 목적입니다.

원본 사실은 `career-hub` 저장소의 `facts/projects/equity-system.md`와
`design/equity-system.md`에 있습니다. 이 저장소는 그 문서들의 확정된
Problem → Decision → Impact만 골라 실제로 동작하는 코드로 옮긴 것입니다.

## 사실과 신규 설계의 구분

모든 코드에 `[FACT 기반 재현]` 또는 `[NEW-DESIGN]` 표시를 클래스/메서드
주석에 남겼습니다.

- **[FACT 기반 재현]**: facts에서 확정된 문제·결정을 그대로 재현한 부분
  (예: 복합 인덱스+대형 유통사 전용 경로+청크화, TTL 없음+무조건 DEL 버그
  → SETNX+Token+TTL, 파싱-검증-매핑-삽입 파이프라인 병렬화)
- **[NEW-DESIGN]**: facts에 "확인 필요"로 남아있던 구체적 수치(청크 크기,
  ThreadPool 크기, TTL 값 등)나, 재현을 위해 새로 지어낸 스키마·도메인
  이름 — **실제 FLO 값이 아닙니다.**

## 재현한 3가지 시나리오

| 시나리오 | Before | After | 테스트 |
|---|---|---|---|
| 대량 삭제 성능 | 인덱스 없음, 단일 트랜잭션 | 복합 인덱스 + 대형 유통사 전용 청크 경로 | `deletion/DeletionPerformanceBenchmarkTest` |
| Redis 분산락 | TTL 없음 + 무조건 DEL | SETNX + Token + TTL (GET-비교-DEL) | `lock/RedisLockRaceConditionTest` |
| 등록 파이프라인 | 단일 스레드 순차 처리 | ThreadPool 병렬 처리 + Backpressure | `registration/RegistrationPipelineBenchmarkTest` |

### 실행 방법

```bash
./gradlew test
```

Docker(Testcontainers)가 필요합니다 — MySQL, Redis 컨테이너를 자동으로
띄우고 종료합니다. 전체 실행에 1~2분 정도 걸립니다.

## 실행하며 실제로 배운 것 (정직하게 남겨둠)

이 랩의 목적 자체가 "이력서 문구가 실제로 맞는지 코드로 검증"이었기
때문에, 검증 과정에서 나온 예상과 다른 결과도 그대로 남깁니다.

### 1. "청크로 나누면 무조건 총 처리시간이 빨라진다"는 틀렸다

처음에는 legacy(단일 트랜잭션)와 optimized(청크 61회)의 **총 처리시간**을
비교했는데, optimized가 오히려 더 오래 걸렸습니다(legacy 1.3초 vs
optimized 3.2~4.7초). 원인은 청크마다 자동 커밋되면서 매번
`innodb_flush_log_at_trx_commit=1`에 의한 fsync 비용이 발생했기
때문입니다 — 61번의 커밋 = 61번의 디스크 동기화.

facts를 다시 보면 실제로 주장하는 개선은 "총 처리시간"이 아니라 **"락
점유 시간"**이었습니다("락 점유 시간 감소로 동시 작업 안정성"). 그래서
검증 대상을 "청크 1회당 최대 소요 시간(=락을 쥐고 있는 시간)"으로
바꿨고, 이 기준으로는 legacy(전체 삭제 시간 전체를 락 점유) 대비
optimized(청크 1회 시간만 락 점유)가 확실히 짧습니다
(`OptimizedDeletionService.DeletionResult.maxSingleStepDurationMs()`).

**교훈**: 청크화의 진짜 가치는 처리량이 아니라 "한 트랜잭션이 시스템을
막는 시간을 줄이는 것"이라는 걸 벤치마크로 직접 확인했습니다. 총
처리시간까지 개선하려면 커밋 빈도와 fsync 비용의 트레이드오프를 별도로
튜닝해야 합니다(예: 청크 크기를 키우거나 `innodb_flush_log_at_trx_commit`
조정 — 이 랩에서는 다루지 않았습니다).

### 2. innodb_buffer_pool_size를 일부러 줄여야 "인덱스 없음"이 실제로 느려진다

60만 건 정도는 MySQL 기본 버퍼풀 설정으로는 통째로 메모리에 올라가
버려서, 인덱스가 있든 없든 둘 다 빨라져 버립니다. `--innodb-buffer-pool-
size=24M`로 강제로 줄여야 "인덱스 없는 전체 스캔"이 실제로 디스크 I/O를
유발해 facts가 말하는 병목이 재현됩니다.

### 3. Testcontainers를 여러 테스트 클래스에서 공유할 때의 함정

`@SpringBootTest` + 공유 `static` 컨테이너 패턴에서, 서로 다른 테스트
클래스가 같은 컨테이너를 가리키는데도 Spring의 ApplicationContext 캐시가
꼬여 HikariCP 풀이 이미 끊어진 커넥션을 물고 있는 상황이 재현됐습니다
(`Failed to obtain JDBC Connection`). `@DirtiesContext(classMode =
AFTER_CLASS)`로 테스트 클래스마다 컨텍스트를 새로 만들도록 강제해
해결했습니다 — 컨테이너 자체는 재시작하지 않으므로 비용은 크지 않습니다.

## 구조

```
src/main/java/com/portfolio/equitylab/
├── domain/EquityShare.java              JPA 엔티티
├── repository/                          JdbcTemplate 기반 DAO
├── deletion/                            시나리오 1
├── lock/                                시나리오 2
├── registration/                        시나리오 3
└── support/EquityShareTestDataGenerator 벤치마크용 시드 데이터
```

## 다음에 추가하면 좋을 것

- 4번째 시나리오: 계약 코드 채번의 다중 인스턴스 충돌
  (`design/mcp-platform-revamp.md` 참고 — 별도 프로젝트로 분리할지 검토)
- 청크 크기별 "총 처리시간 vs 락 점유 시간" 트레이드오프 곡선
- JMH 기반 마이크로벤치마크로 전환(현재는 JUnit 타이머 기반의 매크로벤치마크)
