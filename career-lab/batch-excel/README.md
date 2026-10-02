# batch-excel-lab

FLO(드림어스컴퍼니) 재직 중(2022.9–2022.12) 단독으로 수행한 "대용량 배치·Excel 처리
최적화" 경험을 **재구현 가능한 최소 단위**로 재현한 포트폴리오 랩입니다. 이력서 문구를
코드와 벤치마크로 직접 증명하는 것이 목적입니다.

원본 사실은 `career-hub` 저장소의 `facts/projects/batch-excel-optimization.md`와
`design/batch-excel-optimization.md`에 있습니다. 이 저장소는 그 문서들의 확정된
Problem → Decision → Impact만 골라 실제로 동작하는 코드로 옮긴 것입니다. 같은 방식으로
먼저 만든 `equity-system-lab`(지분율 시스템 트랜잭션 안정성·동시성 개선)의 구조와
교훈을 그대로 이어받았습니다.

## 사실과 신규 설계의 구분

모든 클래스/메서드에 `[FACT 기반 재현]` 또는 `[NEW-DESIGN]` 표시를 남겼습니다.

- **[FACT 기반 재현]**: facts에서 확정된 문제·결정을 그대로 재현한 부분
  (Tasklet 단일 트랜잭션 → 영속성 컨텍스트 누적 → Full GC/OOM, Chunk 전환 +
  flush()/clear(), XSSFWorkbook 전량 적재 → SXSSFWorkbook 스트리밍 전환)
- **[NEW-DESIGN]**: facts에 "확인 필요"로 남아있던 구체적 수치(chunk size,
  SXSSF window size 등)나, 도메인 자체("구독 정산 데이터 집계") — 실제 FLO의
  값·도메인이 아닙니다. `career-hub`의 `design/batch-excel-optimization.md`
  8장 "재구현을 위해 새로 정한 값" 표에서 그대로 가져왔습니다.

## 재현한 2가지 시나리오

| 시나리오 | Before | After | 테스트 |
|---|---|---|---|
| 배치 메모리 안정성 | Tasklet 기반 단일 트랜잭션 (영속성 컨텍스트 누적) | Spring Batch Chunk 기반 (chunk 1,000건마다 flush/clear) | `batch/BatchMemoryBenchmarkTest` |
| Excel 생성 메모리 | XSSFWorkbook 전량 인메모리 적재 | SXSSFWorkbook 스트리밍 (window 500행, 조회 페이지 2,000건) | `excel/ExcelMemoryBenchmarkTest` |

두 시나리오 모두 **"총 처리시간"이 아니라 "힙 메모리 사용량"**을 측정합니다 — facts가
실제로 주장하는 Impact가 메모리 피크 감소(3.8GB→1.6GB, 58%)와 Excel 생성 메모리 감소
(1.2GB→180MB, 85%)이기 때문입니다. `support/MemoryUsageProbe`가 별도 스레드에서
`MemoryMXBean` 기반 힙 사용량을 주기적으로 샘플링해 "관측된 최대치(peak)"를 추적합니다.

### 스코프에서 제외한 것

`design/batch-excel-optimization.md`는 REST API(재실행 엔드포인트, Excel 다운로드
API 등), `settlement_batch`/`excel_export_job` 상태 추적 테이블까지 포함한 훨씬 넓은
설계입니다. 이 랩은 이력서가 실제로 주장하는 두 가지 메모리 개선(NF-1, NF-3)을 코드로
증명하는 데 집중했고, API 계층은 만들지 않았습니다 — `equity-system-lab`도 같은 방식
(서비스 + 벤치마크 테스트, 컨트롤러 없음)으로 만들어져 있어 동일한 패턴을 따랐습니다.
`SettlementRecordKeysetReader`는 design 5.2절대로 `ExecutionContext`에 체크포인트를
남기도록 구현했지만(facts 16행의 "JobRepository 기반 재시작 지원"을 구조적으로
재현), 실패 주입 → 재시작 통합 테스트는 이 랩의 벤치마크 범위 밖이라 별도로 작성하지
않았습니다.

## 실행 방법

이 프로젝트는 `engineering-lab` 모노레포의 `career-lab/batch-excel` 모듈입니다 —
저장소 루트(`engineering-lab/`)에서 실행합니다.

```bash
export JAVA_HOME=/Users/hammac/Library/Java/JavaVirtualMachines/corretto-21.0.7/Contents/Home
./gradlew :career-lab:batch-excel:test
```

Docker(Testcontainers)가 필요합니다 — MySQL 컨테이너를 자동으로 띄우고 종료합니다.
전체 실행에 1~2분 정도 걸립니다(배치 20만 건 × 2회, Excel 10만 건 × 2회 시드 및 처리).

## 실행하며 실제로 배운 것 (정직하게 남겨둠)

### 1. 힙 사용량을 "그냥 스냅샷"하면 개선 효과가 실제보다 훨씬 작게 나온다

처음에는 `MemoryUsageProbe`가 5ms마다 `MemoryMXBean`의 heap used를 그냥 읽어 최댓값을
피크로 기록했습니다. 이 방식으로 배치 시나리오를 측정하니 legacy(Tasklet) delta
215MB, chunk 기반 delta 191MB로 감소율이 **11.2%**밖에 안 나왔습니다 — facts가 주장하는
58%와는 거리가 먼 수치였습니다.

원인은 "아직 GC가 돌지 않아 잠깐 쌓여 있는 가비지"까지 피크에 섞여 들어갔기 때문입니다.
Chunk 방식도 매 청크(1,000건)마다 `Page`/`List` 같은 임시 객체를 만들었다가 버리는데,
GC가 그 순간 마침 돌지 않으면 heap used 스냅샷에는 그 가비지가 그대로 잡힙니다 — 즉
"영속성 컨텍스트가 실제로 붙들고 있는 live set"이 아니라 "그 순간의 할당 압력"을
재고 있었던 것입니다.

샘플마다 `System.gc()`를 먼저 호출해(힌트일 뿐이지만) 가비지를 최대한 걷어낸 뒤 heap
used를 읽도록 바꾸자(간격도 5ms→100ms로 늘림), 결과가 완전히 달라졌습니다:

- **배치**: legacy delta 114.2MB → chunk delta 6.2MB (**94.6% 감소**)
- **Excel**: XSSFWorkbook delta 568.1MB → SXSSFWorkbook delta 1.5MB (**99.7% 감소**)

두 번 연속 전체 테스트를 돌려도 거의 동일한 수치가 재현됐습니다(배치 94.6%/94.6%,
Excel 99.7%/99.7%). **교훈**: "메모리를 측정한다"고 해서 아무 스냅샷이나 믿을 수 있는
건 아니다 — 가비지와 live set을 구분하지 않으면 개선 효과가 실제보다 훨씬 작아(또는
왜곡되어) 보일 수 있다. equity-system-lab에서 "청크로 나누면 총 처리시간이 무조건
빨라진다"는 가정이 틀렸다는 걸 배운 것과 같은 종류의 교훈입니다 — **지표를 무엇을,
어떻게 재는지가 결론을 완전히 바꿔놓는다.**

### 2. 이 랩에서 측정된 감소율(94.6%, 99.7%)은 facts의 58%/85%보다 크다 — 그리고 그게 정상이다

facts의 수치는 실제 FLO 운영 환경(수십만~수백만 건, 특정 서버 스펙, Eclipse MAT로 분석한
실제 프로덕션 힙 덤프)에서 나온 것이고, 이 랩은 로컬 JVM에서 20만~10만 건 규모로
`MemoryMXBean` 스냅샷을 재는 것이라 절대 수치도, 측정 방법론도 다릅니다.
`design/batch-excel-optimization.md` 2.2절이 이미 "절대 수치가 아니라 개선 비율을
재현 목표로 삼는다"고 명시한 이유이기도 합니다. 이 랩이 보여주는 것은 "Chunk
기반 flush/clear"와 "SXSSF 스트리밍"이라는 **기법 자체가 방향성 있게 메모리를
줄인다는 것**이지, FLO에서 정확히 58%/85%가 나왔다는 것을 재현하는 것이 아닙니다 —
오히려 이 랩의 데이터 규모가 상대적으로 작다 보니 "before" 쪽의 영속성 컨텍스트
오버헤드 비중이 더 두드러져 감소율이 더 크게 나온 것으로 보입니다.

### 3. 지분율 랩에서 이미 겪은 함정들은 그대로 재현됐다 (미리 반영해 회피)

- `rewriteBatchedStatements=true` / `useServerPrepStmts=false`를 처음부터 Testcontainers
  URL 파라미터에 넣어뒀다 — 넣지 않았다면 20만 건 INSERT가 오래 걸렸을 것이다.
- 여러 `@SpringBootTest` 클래스가 static MySQL 컨테이너를 공유하되, 클래스마다
  `@DirtiesContext(classMode = AFTER_CLASS)`로 Spring 컨텍스트(및 HikariCP 풀)는
  새로 만들도록 미리 처리해뒀다 — "Failed to obtain JDBC Connection" 재발을 예방했다.

이번 랩에서는 `innodb_buffer_pool_size`를 일부러 줄이지 않았다 — 지분율 랩은
"인덱스 유무에 따른 DB I/O 병목"을 재현하는 것이 목적이라 버퍼풀 크기가 중요했지만,
이 랩은 "애플리케이션 프로세스의 JVM 힙 사용량"을 재는 것이라 버퍼풀 크기와 무관하다.

## 구조

```
src/main/java/com/portfolio/batchlab/
├── domain/SettlementRecord.java                 JPA 엔티티 [NEW-DESIGN 도메인]
├── repository/SettlementRecordRepository.java   keyset 페이지네이션 쿼리
├── batch/
│   ├── BatchProperties.java                     chunk size(1,000)·SXSSF window(500) 등 [NEW-DESIGN] 값
│   ├── legacy/                                  시나리오 1 — Tasklet 단일 트랜잭션 (before)
│   │   ├── LegacyAggregationTasklet.java
│   │   └── LegacyTaskletAggregationJobConfig.java
│   └── chunk/                                   시나리오 1 — Chunk 기반 (after)
│       ├── SettlementRecordKeysetReader.java
│       ├── SettlementAggregationProcessor.java
│       ├── SettlementRecordChunkWriter.java
│       └── ChunkAggregationJobConfig.java
├── excel/                                       시나리오 2
│   ├── LegacyExcelExportService.java             XSSFWorkbook 전량 적재 (before)
│   └── StreamingExcelExportService.java          SXSSFWorkbook 스트리밍 (after)
└── support/
    ├── SettlementRecordTestDataGenerator.java    벤치마크용 시드 데이터 (JdbcTemplate batch insert)
    └── MemoryUsageProbe.java                     힙 사용량 샘플링 유틸 [NEW-DESIGN]

src/test/java/com/portfolio/batchlab/
├── AbstractMySqlIntegrationTest.java             MySQL Testcontainers 공유 베이스
├── batch/BatchMemoryBenchmarkTest.java           시나리오 1 벤치마크
└── excel/ExcelMemoryBenchmarkTest.java           시나리오 2 벤치마크
```

## 다음에 추가하면 좋을 것

- 강제 실패 주입 → `retry`(동일 JobParameters 재실행) → 이미 커밋된 chunk는 재처리하지
  않는지 검증하는 통합 테스트 (design 6.1절 시퀀스 다이어그램 재현)
- REST API 계층(`design/batch-excel-optimization.md` 4장) — 필요해지면 별도로 추가
- JFR(Java Flight Recorder) 기반 측정으로 전환해 `MemoryUsageProbe`의 `System.gc()`
  의존도를 낮추기
- chunk size·SXSSF window size를 바꿔가며 메모리/처리시간 트레이드오프 곡선 그리기
