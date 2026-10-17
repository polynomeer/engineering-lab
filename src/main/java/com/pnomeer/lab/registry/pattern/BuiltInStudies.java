package com.pnomeer.lab.registry.pattern;

import java.util.List;
import static com.pnomeer.lab.registry.pattern.StudyContribution.*;

/** Source-grounded descriptions; workload fixtures remain the original scenario resources. */
public final class BuiltInStudies {
    private BuiltInStudies() {}
    public static StudyContribution contribution() {
        return new StudyContribution(List.of(
            new Study("pipeline.excel-ingestion",
                new CaseStudy("pipeline.excel-ingestion","docs/scenarios.md","NEW-DESIGN: 플랫폼의 합성 입력 기반 실험이다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("sequential","단일 스레드 처리","SINGLE_THREAD: 읽기·검증·변환·청크 저장을 한 스레드에서 수행한다."),
                    new Strategy("pipeline","단계별 파이프라인","PIPELINE: bounded queue와 검증·저장 worker로 단계 간 backpressure를 전달한다.")),
                List.of(
                    new Preset("small-batch", List.of("pipeline"), "experiments/pipeline/excel-ingestion/scenarios/small-batch.yaml"))),
            new Study("concurrency.queue-contention",
                new CaseStudy("concurrency.queue-contention","docs/scenarios.md","NEW-DESIGN: 플랫폼의 합성 입력 기반 실험이다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("bounded-queue","용량 제한 큐","producer/consumer 수와 소비 지연을 바꾸며 같은 bounded queue 구현의 대기를 관측한다.")),
                List.of(
                    new Preset("small-buffer", List.of("bounded-queue"), "experiments/concurrency/queue-contention/scenarios/small-buffer.yaml"))),
            new Study("io.endpoint-comparison",
                new CaseStudy("io.endpoint-comparison","docs/scenarios.md","NEW-DESIGN: 플랫폼의 합성 입력 기반 실험이다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("virtual-thread","Virtual thread 대기","가상 스레드 executor의 sleep 작업 완료를 기다린다."),
                    new Strategy("reactor-delay","Reactor delay 대기","Mono.delay(...).block()을 같은 입력으로 순차 측정한다. 실제 HTTP·selector 서버 부하 실험이 아니다.")),
                List.of(
                    new Preset("baseline", List.of("virtual-thread","reactor-delay"), "experiments/io/endpoint-comparison/scenarios/baseline.json"))),
            new Study("career.mcp-platform.sequence",
                new CaseStudy("career.mcp-platform.sequence","career-lab/mcp-platform/README.md","모듈 README가 구분한 FACT 기반 메커니즘 재현과 NEW-DESIGN 입력·스키마·수치. 실제 운영 코드나 성과 측정이 아니다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("non-atomic","비원자적 범위 할당","BEFORE: SELECT 후 별도 UPDATE, 경쟁 구간에 합성 50ms 지연을 둔다."),
                    new Strategy("atomic-update","원자적 UPDATE","ATOMIC_UPDATE: LAST_INSERT_ID(value + N)를 사용한 단일 UPDATE로 할당한다."),
                    new Strategy("row-lock","행 잠금 할당","SELECT_FOR_UPDATE: SELECT FOR UPDATE와 트랜잭션으로 범위를 할당한다.")),
                List.of(
                    new Preset("atomic-update", List.of("atomic-update"), "experiments/career-sequence/scenarios/atomic-update.json"))),
            new Study("career.mcp-platform.architecture",
                new CaseStudy("career.mcp-platform.architecture","career-lab/mcp-platform/README.md","모듈 README가 구분한 FACT 기반 메커니즘 재현과 NEW-DESIGN 입력·스키마·수치. 실제 운영 코드나 성과 측정이 아니다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("dependency-rules","실행 가능한 의존 규칙","동일 ArchUnit 규칙을 COMPLIANT/VIOLATION fixture에 적용한다. 위반 감지는 기대 성공이다.")),
                List.of(
                    new Preset("architecture-compliant", List.of("dependency-rules"), "experiments/career/mcp-platform/scenarios/architecture-compliant.json"),
                    new Preset("architecture-violation", List.of("dependency-rules"), "experiments/career/mcp-platform/scenarios/architecture-violation.json"))),
            new Study("career.mcp-platform.validation",
                new CaseStudy("career.mcp-platform.validation","career-lab/mcp-platform/README.md","모듈 README가 구분한 FACT 기반 메커니즘 재현과 NEW-DESIGN 입력·스키마·수치. 실제 운영 코드나 성과 측정이 아니다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("layered-validation","3단계 검증","Bean Validation → DTO 매핑 → 도메인 규칙을 적용한다. 거절 fixture는 전략 변경이 아니다.")),
                List.of(
                    new Preset("validation-blank-name", List.of("layered-validation"), "experiments/career/mcp-platform/scenarios/validation-blank-name.json"),
                    new Preset("validation-domain-rejection", List.of("layered-validation"), "experiments/career/mcp-platform/scenarios/validation-domain-rejection.json"),
                    new Preset("validation-past-expiry", List.of("layered-validation"), "experiments/career/mcp-platform/scenarios/validation-past-expiry.json"),
                    new Preset("validation-valid", List.of("layered-validation"), "experiments/career/mcp-platform/scenarios/validation-valid.json"))),
            new Study("career.equity-system.deletion",
                new CaseStudy("career.equity-system.deletion","career-lab/equity-system/README.md","모듈 README가 구분한 FACT 기반 메커니즘 재현과 NEW-DESIGN 입력·스키마·수치. 실제 운영 코드나 성과 측정이 아니다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("unindexed","인덱스 없는 전체 삭제","BEFORE: 복합 인덱스 없이 단일 삭제 트랜잭션을 실행한다."),
                    new Strategy("indexed-chunks","인덱스와 청크 삭제","AFTER: 복합 인덱스와 대형 유통사 청크 경로를 함께 적용한다. 인덱스 단독 효과가 아니다.")),
                List.of(
                    new Preset("deletion-after", List.of("indexed-chunks"), "experiments/career/equity-system/scenarios/deletion-after.json"),
                    new Preset("deletion-before", List.of("unindexed"), "experiments/career/equity-system/scenarios/deletion-before.json"))),
            new Study("career.equity-system.redis-lock",
                new CaseStudy("career.equity-system.redis-lock","career-lab/equity-system/README.md","모듈 README가 구분한 FACT 기반 메커니즘 재현과 NEW-DESIGN 입력·스키마·수치. 실제 운영 코드나 성과 측정이 아니다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("unsafe-lock","TTL 없는 잠금","BEFORE: TTL이 없고 소유권 확인 없이 DEL한다."),
                    new Strategy("ownership-lock","소유권과 TTL 잠금","AFTER: SETNX, 소유 토큰, TTL과 소유권 확인 후 해제를 사용한다. 모든 경쟁 조건의 증명은 아니다.")),
                List.of(
                    new Preset("redis-lock-after", List.of("ownership-lock"), "experiments/career/equity-system/scenarios/redis-lock-after.json"),
                    new Preset("redis-lock-before", List.of("unsafe-lock"), "experiments/career/equity-system/scenarios/redis-lock-before.json"))),
            new Study("career.equity-system.registration",
                new CaseStudy("career.equity-system.registration","career-lab/equity-system/README.md","모듈 README가 구분한 FACT 기반 메커니즘 재현과 NEW-DESIGN 입력·스키마·수치. 실제 운영 코드나 성과 측정이 아니다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("sequential","순차 등록","BEFORE: 단일 스레드에서 파싱·검증·매핑·저장한다."),
                    new Strategy("parallel","제한된 병렬 등록","AFTER/INVALID_ROW: 제한된 병렬 처리와 backpressure, 청크 저장을 사용한다.")),
                List.of(
                    new Preset("registration-after", List.of("parallel"), "experiments/career/equity-system/scenarios/registration-after.json"),
                    new Preset("registration-before", List.of("sequential"), "experiments/career/equity-system/scenarios/registration-before.json"),
                    new Preset("registration-invalid-row", List.of("parallel"), "experiments/career/equity-system/scenarios/registration-invalid-row.json"))),
            new Study("career.batch-excel.batch-memory",
                new CaseStudy("career.batch-excel.batch-memory","career-lab/batch-excel/README.md","모듈 README가 구분한 FACT 기반 메커니즘 재현과 NEW-DESIGN 입력·스키마·수치. 실제 운영 코드나 성과 측정이 아니다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("tasklet","Tasklet 전체 처리","BEFORE: 단일 트랜잭션에서 영속성 컨텍스트가 누적되는 대조군이다."),
                    new Strategy("chunk","Chunk 처리","AFTER/FAIL_AFTER_MEASUREMENT: 청크 경계에서 flush/clear한다. 실패 주입은 구현 전략이 아니다.")),
                List.of(
                    new Preset("batch-memory-after", List.of("chunk"), "experiments/career/batch-excel/scenarios/batch-memory-after.json"),
                    new Preset("batch-memory-before", List.of("tasklet"), "experiments/career/batch-excel/scenarios/batch-memory-before.json"),
                    new Preset("batch-memory-fail-after-measurement", List.of("chunk"), "experiments/career/batch-excel/scenarios/batch-memory-fail-after-measurement.json"))),
            new Study("career.batch-excel.excel-memory",
                new CaseStudy("career.batch-excel.excel-memory","career-lab/batch-excel/README.md","모듈 README가 구분한 FACT 기반 메커니즘 재현과 NEW-DESIGN 입력·스키마·수치. 실제 운영 코드나 성과 측정이 아니다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("in-memory","XSSFWorkbook","BEFORE: workbook을 메모리에 유지한다."),
                    new Strategy("streaming","SXSSFWorkbook","AFTER/FAIL_AFTER_MEASUREMENT: 제한된 행 창과 페이지 조회로 생성한다.")),
                List.of(
                    new Preset("excel-memory-after", List.of("streaming"), "experiments/career/batch-excel/scenarios/excel-memory-after.json"),
                    new Preset("excel-memory-before", List.of("in-memory"), "experiments/career/batch-excel/scenarios/excel-memory-before.json"),
                    new Preset("excel-memory-fail-after-measurement", List.of("streaming"), "experiments/career/batch-excel/scenarios/excel-memory-fail-after-measurement.json"))),
            new Study("career.mds-distribution.settlement-guard",
                new CaseStudy("career.mds-distribution.settlement-guard","career-lab/mds-distribution/README.md","모듈 README가 구분한 FACT 기반 메커니즘 재현과 NEW-DESIGN 입력·스키마·수치. 실제 운영 코드나 성과 측정이 아니다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("unguarded","가드 없는 수정","BEFORE: 정산 상태를 확인하지 않고 메타데이터를 수정한다."),
                    new Strategy("guarded","상태 가드와 TTL","AFTER/TTL_RECOVERY: 상태 플래그와 AOP 가드, TTL 지연 평가를 사용한다.")),
                List.of(
                    new Preset("settlement-guard-after", List.of("guarded"), "experiments/career/mds-distribution/scenarios/settlement-guard-after.json"),
                    new Preset("settlement-guard-before", List.of("unguarded"), "experiments/career/mds-distribution/scenarios/settlement-guard-before.json"),
                    new Preset("settlement-guard-ttl-recovery", List.of("guarded"), "experiments/career/mds-distribution/scenarios/settlement-guard-ttl-recovery.json"))),
            new Study("career.mds-distribution.idempotency",
                new CaseStudy("career.mds-distribution.idempotency","career-lab/mds-distribution/README.md","모듈 README가 구분한 FACT 기반 메커니즘 재현과 NEW-DESIGN 입력·스키마·수치. 실제 운영 코드나 성과 측정이 아니다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("unique-message","메시지 ID 중복 제거","메시지 ID DB 유니크 제약을 순차·동시·실패 후 재시도 조건으로 검증한다. 실제 broker를 사용하지 않는다.")),
                List.of(
                    new Preset("idempotency-concurrent", List.of("unique-message"), "experiments/career/mds-distribution/scenarios/idempotency-concurrent.json"),
                    new Preset("idempotency-processing-failure", List.of("unique-message"), "experiments/career/mds-distribution/scenarios/idempotency-processing-failure.json"),
                    new Preset("idempotency-retry-after-failure", List.of("unique-message"), "experiments/career/mds-distribution/scenarios/idempotency-retry-after-failure.json"),
                    new Preset("idempotency-sequential", List.of("unique-message"), "experiments/career/mds-distribution/scenarios/idempotency-sequential.json"))),
            new Study("career.creator-studio.session-rotation",
                new CaseStudy("career.creator-studio.session-rotation","career-lab/creator-studio/README.md","모듈 README가 구분한 FACT 기반 메커니즘 재현과 NEW-DESIGN 입력·스키마·수치. 실제 운영 코드나 성과 측정이 아니다.","개선 설계의 재구현이며 토큰은 합성 opaque 문자열이다. 실제 JWT 서명 검증이 아니다."),
                List.of(new Strategy("legacy-session","원문 토큰 세션","BEFORE: 이전 토큰 세션이 남는 구조를 재현한다."),
                    new Strategy("atomic-rotation","원자적 세션 교체","AFTER/CONCURRENT: 토큰 hash, 사용자·기기 역인덱스와 Lua 교체를 사용한다.")),
                List.of(
                    new Preset("session-rotation-after", List.of("atomic-rotation"), "experiments/career/creator-studio/scenarios/session-rotation-after.json"),
                    new Preset("session-rotation-before", List.of("legacy-session"), "experiments/career/creator-studio/scenarios/session-rotation-before.json"),
                    new Preset("session-rotation-concurrent", List.of("atomic-rotation"), "experiments/career/creator-studio/scenarios/session-rotation-concurrent.json"))),
            new Study("career.creator-studio.mapping-integrity",
                new CaseStudy("career.creator-studio.mapping-integrity","career-lab/creator-studio/README.md","모듈 README가 구분한 FACT 기반 메커니즘 재현과 NEW-DESIGN 입력·스키마·수치. 실제 운영 코드나 성과 측정이 아니다.","원인 가설의 재현이며 실제 장애 근본 원인을 확정하지 않는다."),
                List.of(new Strategy("overwrite","무조건 관계 덮어쓰기","BEFORE: 미확정 근본 원인 가설을 대조군으로 재현한다."),
                    new Strategy("explicit-transfer","가입과 명시적 이관 분리","AFTER/TRANSFER: 가입·관계 변경 진입점을 분리하고 콘텐츠 이관 확인과 변경 이력을 적용한다.")),
                List.of(
                    new Preset("mapping-integrity-after", List.of("explicit-transfer"), "experiments/career/creator-studio/scenarios/mapping-integrity-after.json"),
                    new Preset("mapping-integrity-before", List.of("overwrite"), "experiments/career/creator-studio/scenarios/mapping-integrity-before.json"),
                    new Preset("mapping-integrity-transfer", List.of("explicit-transfer"), "experiments/career/creator-studio/scenarios/mapping-integrity-transfer.json"))),
            new Study("career.creator-studio.post-commit",
                new CaseStudy("career.creator-studio.post-commit","career-lab/creator-studio/README.md","모듈 README가 구분한 FACT 기반 메커니즘 재현과 NEW-DESIGN 입력·스키마·수치. 실제 운영 코드나 성과 측정이 아니다.","구체적인 fixture·측정 범위에서만 해석한다. 실험 수치를 실제 업무 성과로 대체하지 않는다."),
                List.of(new Strategy("after-commit","커밋 이후 외부 전달","AFTER_COMMIT listener와 제한된 외부 stub을 사용한다. COMMIT/ROLLBACK/RETRY/EXHAUSTED는 실행 조건이며 durable outbox가 아니다.")),
                List.of(
                    new Preset("post-commit-commit", List.of("after-commit"), "experiments/career/creator-studio/scenarios/post-commit-commit.json"),
                    new Preset("post-commit-exhausted", List.of("after-commit"), "experiments/career/creator-studio/scenarios/post-commit-exhausted.json"),
                    new Preset("post-commit-retry", List.of("after-commit"), "experiments/career/creator-studio/scenarios/post-commit-retry.json"),
                    new Preset("post-commit-rollback", List.of("after-commit"), "experiments/career/creator-studio/scenarios/post-commit-rollback.json")))
        ), List.of());
    }
}
