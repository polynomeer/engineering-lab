package com.pnomeer.lab.registry.measurement;

import java.util.*;
import static com.pnomeer.lab.registry.measurement.MeasurementContract.*;

public final class BuiltInMeasurements {
    private BuiltInMeasurements() { }
    private static final Property BOUNDED = new Property("bounded-memory", "입력 증가에도 버퍼·동시 작업 수에 상한이 있다", "queue depth / in-flight / sampled heap과 설정 상한을 함께 확인; 샘플 최댓값은 실제 peak의 증명이 아니다");
    private static final Property INTEGRITY = new Property("data-integrity", "처리 결과의 누락·중복·참조 불일치를 검출한다", "입력·저장 개수, dead-letter, 해당 worker의 CHECK·artifact 확인; 실행 성공만으로 성립하지 않는다");
    private static final Property EXCLUSION = new Property("mutual-exclusion", "경합 중 보호 구간의 동시 실행을 제한한다", "overlap·최종 값과 경쟁 조건에 대한 CHECK 확인");
    private static final Property ISOLATION = new Property("failure-isolation", "실패·커밋 경계 밖의 부작용을 분리한다", "실패 주입 variant의 CHECK·DB 결과·artifact 확인");
    private static final Property REPEAT = new Property("idempotency", "재시도·중복 요청에도 효과가 중복되지 않는다", "반복 호출·중복 키 시나리오의 CHECK와 최종 저장 결과 확인");
    private static final Property WORK = new Property("work-completion", "요청한 작업의 완료 수를 확인한다", "완료·실패 수와 입력 수를 대조; 처리율만으로 정확성을 판정하지 않는다");
    public static MeasurementContribution contribution() {
        var list = new ArrayList<MeasurementContract>();
        list.add(contract("pipeline.excel-ingestion", List.of(BOUNDED, INTEGRITY), List.of(
                measure("elapsed", Kind.gauge, "timing.elapsedMs", "ms", "pipeline", "elapsed", "PipelineRunner 처리 시작부터 종료까지; XLSX 업로드·Spring 시작 제외"),
                measure("completed", Kind.counter, "rows.inserted", "rows", "pipeline", "inserted-rows", "동일 처리 구간의 저장 행 누계")), List.of("input.sha256", "measurement.mode", "mode"), "DB 상태·캐시·외부 부하의 동일성은 자동 증명하지 않는다"));
        list.add(contract("concurrency.queue-contention", List.of(BOUNDED, WORK), List.of(
                measure("elapsed", Kind.gauge, "time.elapsedMs", "ms", "queue", "elapsed", "생산자·소비자 작업 시작부터 완료까지"),
                measure("throughput", Kind.gauge, "throughput.itemsPerSec", "items/s", "queue", "produced-per-elapsed", "생산 수 / 전체 경과 ms; 0ms이면 생산 수 사용")),
                List.of("queueCapacity", "producerThreads", "consumerThreads", "itemsPerProducer", "consumerDelayMs", "measurement.mode"), "스케줄링·관측 오버헤드 포함; 단일 Run은 통계적 근거가 아니다"));
        list.add(contract("io.endpoint-comparison", List.of(WORK), List.of(
                measure("throughput", Kind.gauge, "throughput.totalRequestsPerSec", "requests/s", "synthetic-io", "completed-per-summed-task-duration", "virtual sleep + Reactor delay.block 작업 지연 합을 분모로 사용; wall-clock 처리율 아님")),
                List.of("measurement.mode", "requests", "delayMs", "pinning", "pinDelayMs"), "합성 대기 비교이며 실제 네트워크 endpoint 부하 시험이 아니다"));
        list.add(contract("career.mcp-platform.sequence", List.of(EXCLUSION, INTEGRITY), List.of(
                measure("elapsed", Kind.counter, "sequence.elapsedNanos", "ns", "worker-harness", "elapsed", "sequence harness 실행 구간; worker 시작 제외; BEFORE 인위적 지연 포함")), List.of("variant", "seed"), "순서·중첩 CHECK와 함께 해석; 인위적 지연으로 일반 성능 우위를 주장하지 않는다"));
        for (String name : List.of("batch-memory", "excel-memory")) list.add(contract("career.batch-excel." + name, List.of(BOUNDED, INTEGRITY), List.of(
                measure("heap", Kind.counter, "memory.sampledPeakBytes", "bytes", "worker-process", "sampled-heap-maximum", name + " 작업 중 MemoryMXBean heap used, 20ms 주기와 경계 샘플; 명시적 GC 없음; 시작·seed·검증·정리 제외"),
                measure("elapsed", Kind.counter, "memory.elapsedNanos", "ns", "worker-operation", "elapsed", name + " JobLauncher.run 또는 export 구간; warmup 없음")), List.of("variant", "rows", "seed"), "샘플 heap은 RSS·live-set·실제 peak와 다르며 GC와 샘플 주기에 영향받는다"));
        check(list, "mcp-platform.architecture", List.of(ISOLATION));
        check(list, "mcp-platform.validation", List.of(INTEGRITY));
        check(list, "equity-system.deletion", List.of(INTEGRITY, ISOLATION));
        check(list, "equity-system.redis-lock", List.of(EXCLUSION));
        check(list, "equity-system.registration", List.of(BOUNDED, INTEGRITY));
        check(list, "mds-distribution.settlement-guard", List.of(INTEGRITY, ISOLATION));
        check(list, "mds-distribution.idempotency", List.of(REPEAT));
        check(list, "creator-studio.session-rotation", List.of(ISOLATION, INTEGRITY));
        check(list, "creator-studio.mapping-integrity", List.of(INTEGRITY));
        check(list, "creator-studio.post-commit", List.of(ISOLATION));
        return new MeasurementContribution(list);
    }
    private static void check(List<MeasurementContract> list, String suffix, List<Property> properties) {
        list.add(contract("career." + suffix, properties, List.of(), List.of("variant", "seed"), "CHECK 중심 검증. 원시 timing이 있어도 이 계약은 성능 비교를 선언하지 않는다. DB·broker·외부 부하 동일성은 미기록"));
    }
    private static MeasurementContract contract(String id, List<Property> properties, List<Measure> measures, List<String> required, String limitations) {
        return new MeasurementContract(1, id, "1.0.0", properties, measures, required, limitations);
    }
    private static Measure measure(String role, Kind kind, String name, String unit, String scope, String semantics, String window) {
        return new Measure(role, kind, name, unit, scope, semantics, window);
    }
}
