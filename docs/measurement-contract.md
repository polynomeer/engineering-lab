# 검증 성질·측정 계약과 비교 조건 (P4)

## 목적과 구분

패턴이 같다는 이유만으로 두 실행을 성능 순위에 올리지 않는다. P4는 실행 당시 선언한
측정 의미와 기록된 조건을 대조한다. 검증 성질은 **목표**이며 PASS 판정이 아니다.
실제 정확성은 해당 Run의 CHECK·dead-letter·요약·artifact를 확인한다.

- `Property(id, meaning, evidence)`: 재사용할 성질과 확인할 증거 안내.
- `Measure(role, kind, name, unit, scope, semantics, window)`: 최종 summary에서 읽을 지표,
  단위·대상·의미·측정 구간. 같은 role이어도 모든 선언 필드가 같아야 일치한다.
- `MeasurementContract`: schemaVersion=1, experimentId, methodVersion, properties,
  measures, requiredParameters, limitations. 방법이나 구간이 바뀌면 methodVersion을 올린다.
- 공통 성질: bounded-memory, data-integrity, mutual-exclusion, failure-isolation,
  idempotency, work-completion. 같은 ID를 다른 의미/증거 안내로 재등록하면 시작을 거부한다.
  성질 ID가 바뀌어야 할 의미 변경은 새로운 ID로 등록한다.

## 실행 당시 계약 보존

Spring `MeasurementContribution` bean으로 실험별 계약을 등록한다. 기본 등록은 16개 실험을
포함하고, API에는 활성 ExperimentCatalog의 계약만 노출한다. 계약이 없는 신규 실험도 실행할
수 있다. `MeasurementCatalog`는 실행 준비 시 `RunMetadata.parameters["measurement.contract.v1"]`에
JSON 스냅샷을 넣는다. 실행 후 카탈로그 변경은 이미 저장된 계약을 바꾸지 않는다.
기존 생성자/독립 runner는 계약 공급자를 생략할 수 있으며 그 Run은 계약 미기록으로 남는다.

기존 scenario JSON/canonical/hash, Run ID, DB schema, worker wire protocol, export envelope는
바꾸지 않는다. 신규 Run에만 선택 metadata 키가 추가된다. 구형 Run은 백필하지 않는다.
기존 `/runs/compare`, `/metrics/schema` 및 export 응답의 구조도 유지한다.
`/metrics/schema`의 현재 정의를 과거 실행 당시 의미로 간주하면 안 된다.

상한은 contribution당 256개, contribution 256개, 전체 계약 4096개, 계약별 properties 16개,
measures 32개, requiredParameters 64개다. 텍스트 1000자·ID 100자·스냅샷 UTF-8 16 KiB를 넘거나,
실험/role/property/필수 조건이 중복되면 등록을 거부한다. 목록은 불변 복사한다.

## 기본 선언 범위

| 실험 | 선언 지표 / 측정 구간 | 공통 성질 |
| --- | --- | --- |
| pipeline.excel-ingestion (1.1.0) | elapsed ms, inserted rows, inserted rows/s, sampled peak heap bytes / PipelineRunner 처리 구간. 서버 내 실행의 heap은 JVM 전체 값 | bounded-memory, data-integrity |
| concurrency.queue-contention | elapsed ms, produced items/s / 생산·소비 작업 구간 | bounded-memory, work-completion |
| io.endpoint-comparison | requests/s / virtual sleep + Reactor delay.block의 작업 지연 합 | work-completion |
| career.mcp-platform.sequence | elapsed ns / sequence harness, BEFORE 인위적 지연 포함 | mutual-exclusion, data-integrity |
| career.batch-excel.batch-memory, excel-memory | heap bytes, elapsed ns / JobLauncher.run 또는 export, cold 실행 | bounded-memory, data-integrity |
| career.mcp-platform.architecture | CHECK 중심 | failure-isolation |
| career.mcp-platform.validation | CHECK 중심 | data-integrity |
| career.equity-system.deletion | CHECK 중심 | data-integrity, failure-isolation |
| career.equity-system.redis-lock | CHECK 중심 | mutual-exclusion |
| career.equity-system.registration | CHECK 중심 | bounded-memory, data-integrity |
| career.mds-distribution.settlement-guard | CHECK 중심 | data-integrity, failure-isolation |
| career.mds-distribution.idempotency | CHECK 중심 | idempotency |
| career.creator-studio.session-rotation | CHECK 중심 | failure-isolation, data-integrity |
| career.creator-studio.mapping-integrity | CHECK 중심 | data-integrity |
| career.creator-studio.post-commit | CHECK 중심 | failure-isolation |

CHECK 중심은 실행 중 timing이 전혀 없다는 뜻이 아니다. P4의 성능 비교 계약에는 아직 포함하지
않았다는 뜻이다. 원시 요약은 계속 볼 수 있다. 선언되지 않은 지표를 이름으로 추론하지 않는다.
메모리는 MemoryMXBean heap used의 20ms·경계 샘플 최댓값이며 RSS/live-set/진짜 peak가 아니다.
seed·시작·검증·정리 시간을 제외하지만 메모리에는 기존 객체나 아직 GC되지 않은 쓰레기가 남을 수 있다.
IO 분모는 wall-clock elapsed가 아니라 작업 지연 합이므로 실제 HTTP 부하 처리율과 다르다.

## 읽기 전용 API

- `GET /dashboard/v1/measurement-catalog`: `{schemaVersion:1, contracts:[...]}`. 현재 등록 안내.
- `GET /dashboard/v1/comparison-assessment?baseline=<runId>&candidate=<runId>`:
  `{schemaVersion:1, baselineId, candidateId, state, baselineContract, candidateContract, conditions, measures}`.
  없는 Run은 404, 누락 query는 400. 과거 Run의 계약 없음은 정상 200 + null 계약이다.

`conditions`는 `{field,status}` 목록, status는 MATCH/MISMATCH/UNKNOWN이다. `measures`는 role별로
양쪽 선언·최종 값·status를 제공한다. 값 없음/비유한 값은 null이며 0으로 대체하지 않는다.
단위·scope·semantics·window 차이는 각각 조건으로 설명한다. 값의 크기 자체는 조건 일치 판정과 별개다.

전체 state는 불일치가 하나라도 있으면 REVIEW_REQUIRED, 불일치 없이 미확인이 있으면
INSUFFICIENT_EVIDENCE, 모두 일치할 때만 RECORDED_CONDITIONS_MATCH다. 불일치와 미확인은 동시에
존재할 수 있으므로 전체 state와 조건 목록을 함께 읽는다. 어떤 state도 자동 우위·인과·통계적
유의성·Property 통과를 뜻하지 않는다. 단위 환산이나 향상률은 자동 계산하지 않는다.

대조 대상은 다음과 같다.

- 서로 다른 Run인지, 성공·증거 완료인지, 실험·방법 버전·선언된 성질/한계가 같은지.
- execution kind/module/protocol/scope, 실제 실행 대상의 JVM/OS/CPU 수/heap,
  clean Git, 외부 worker artifact SHA-256. worker가 없으면 controller 정보로 대체하지 않는다.
- 양쪽 metadata 조건의 합집합과 계약상 requiredParameters. 입력 hash/규모/seed/variant/실패 조건/
  관측 설정/정의 hash가 있으면 모두 대조한다. 의도한 전략 차이도 숨기거나 자동 승인하지 않는다.
- 원본 `scenario.definition`과 스냅샷 JSON 문자열은 조건 목록에서 제외한다. 정규화된 파라미터와
  `scenario.definition.sha256`는 계속 비교한다. 256개 조건 키까지만 평가하며 초과 시 UNKNOWN이다.
- 외부 worker 또는 pipeline DB의 의존 서비스 환경은 완전한 지문이 없으므로 UNKNOWN이다.
  JVM 정보가 같아도 DB/broker/cache/호스트 부하가 같다고 판단하지 않는다.

계약이 없거나 잘못됐거나 지원하지 않는 버전/실험이면 null + UNKNOWN이다. `unknown`으로 선언한
단위·범위·의미도 미확인이다. 현재 카탈로그로 보충하지 않는다. CHECK 페이지나 타임라인 전체를
메모리에 수집하지 않고 저장된 Run/Execution/제한된 스냅샷만 읽는다.

## 확장 예시

```java
@Bean MeasurementContribution demoMeasurements() {
    return new MeasurementContribution(List.of(new MeasurementContract(
        1, "custom.demo", "1.0.0",
        List.of(new MeasurementContract.Property("custom-completion", "작업 완료", "완료 수와 입력 수 대조")),
        List.of(new MeasurementContract.Measure("elapsed", MeasurementContract.Kind.gauge,
            "custom.elapsedMs", "ms", "worker-operation", "elapsed", "작업 시작부터 완료까지")),
        List.of("seed", "rows"), "합성 입력; 외부 부하 미기록")));
}
```

실제 emitter의 kind·단위·시작/종료 지점부터 확인한다. 이름만 같다고 역할/단위를 부여하지 않는다.
등록 bean·측정 snapshot·조건 mismatch/unknown·기존 Run 재개방 회귀를 함께 추가한다.

## 대시보드와 검증

비교 화면에서 양쪽 계약을 별도 표시하고, 조건별 판정·검증 목표·JSON 다운로드를 제공한다.
새 API의 404/미지원 버전은 기존 비교를 유지한다. 네트워크/5xx/잘못된 v1은 오류와 재시도를
제공한다. 아래 원시 비교 표의 단위는 현재 서버 카탈로그 참고값임을 명시한다.

테스트: MeasurementCatalogTest, ComparisonAssessmentTest, ScenarioApiIntegrationTest,
RunRestartIntegrationTest, frontend measurement.test.ts와 tests/measurement.spec.ts.
이 단계는 Career 구현·worker image·운영 배포를 교체하지 않는다. 전체 모듈 실제 실행 인수는 P7에서 수행한다.
