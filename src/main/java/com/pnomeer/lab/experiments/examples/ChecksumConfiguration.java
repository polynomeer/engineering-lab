package com.pnomeer.lab.experiments.examples;

import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.registry.pattern.*;
import com.pnomeer.lab.registry.measurement.*;
import com.pnomeer.lab.metrics.MetricCatalog;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;
import static com.pnomeer.lab.experiments.examples.BatchedChecksumExperiment.ID;
import static com.pnomeer.lab.registry.ParameterSpec.integer;
import static com.pnomeer.lab.registry.pattern.StudyContribution.*;

@Configuration
@ConditionalOnProperty(name="lab.examples.checksum.enabled", havingValue="true")
public class ChecksumConfiguration {
    public static final ExperimentDescriptor DESCRIPTOR = new ExperimentDescriptor(ID,"Batched checksum","EXAMPLE","NONE",
            Map.of("rows",integer(1,10000,"rows"),"batchSize",integer(1,256,"rows"),"seed",integer(0,1000000,"seed"),"failBatch",integer(0,10000,"batch; 0 disables failure")),List.of("SINGLE_PASS"));
    @Bean public ExperimentModule checksumModule() {
        return new ExperimentModule() {
            public ModuleDescriptor module() { return new ModuleDescriptor("checksum-example","Batch reuse example","examples"); }
            public List<ExperimentDescriptor> experiments() { return List.of(DESCRIPTOR); }
            public List<MetricCatalog.Definition> metrics() { return List.of(
                    metric("checksum.rows","rows","RUN_CUMULATIVE"), metric("checksum.value","checksum","RUN_CUMULATIVE"),
                    metric("checksum.batches","batches","RUN_CUMULATIVE"), metric("checksum.maxBatchRows","rows","OBSERVED_MAXIMUM")); }
            private MetricCatalog.Definition metric(String name,String unit,String semantics) {
                return new MetricCatalog.Definition(name,unit,"synthetic-checksum",semantics,"Use final value; never sum cumulative samples");
            }
        };
    }
    @Bean public ExperimentRegistration<BatchedChecksumExperiment.Scenario> checksumRegistration() {
        return new ExperimentRegistration<>(DESCRIPTOR, d -> new BatchedChecksumExperiment(), (d,input) -> {
            var p=d.parameters();
            return new BatchedChecksumExperiment.Scenario(d.scenario().id(),n(p,"rows"),n(p,"batchSize"),n(p,"seed"),n(p,"failBatch"));
        });
    }
    private static int n(Map<String,Object> p,String key) { return ((Number)p.get(key)).intValue(); }
    @Bean public PatternContribution checksumPatterns() {
        return new PatternContribution(List.of(),List.of(),List.of(new PatternContribution.Binding(ID,
                new PatternContribution.Classification("data-processing",List.of("chunked-write","streaming")))));
    }
    @Bean public StudyContribution checksumStudies() {
        return new StudyContribution(List.of(new Study(ID,new CaseStudy("Generated record checksum","experiments/examples/batched-checksum/README.md",
                "NEW-DESIGN: 패턴 재사용 검증용 합성 레코드와 메모리 checksum sink", "DB·네트워크·병렬 성능 및 실제 업무 성과를 측정하지 않는다. checksum은 충돌 가능하며 완전한 무결성 증명이 아니다."),
                List.of(new Strategy("row-write","행별 쓰기","batchSize=1로 매 행 동기 sink 호출"),
                        new Strategy("batched-write","제한된 배치 쓰기","동일 BoundedBatch에 최대 batchSize행을 담아 동기 sink 호출, 입력 종료 시 tail flush")),
                List.of(new Preset("row-write",List.of("row-write"),path("row-write")),
                        new Preset("partial-batch",List.of("batched-write"),path("partial-batch")),
                        new Preset("sink-failure",List.of("batched-write"),path("sink-failure"))))),List.of());
    }
    private static String path(String name) { return "experiments/examples/batched-checksum/scenarios/"+name+".json"; }
    @Bean public MeasurementContribution checksumMeasurements() {
        return new MeasurementContribution(List.of(new MeasurementContract(1,ID,"1.0.0",List.of(
                new MeasurementContract.Property("checksum-conservation","생성 레코드 수와 checksum을 배치 크기에 무관하게 보존한다","rows 및 seed로 예상 합을 계산하고 최종 rows/value와 대조; checksum 충돌 한계 유지")),
                List.of(new MeasurementContract.Measure("completed",MeasurementContract.Kind.counter,"checksum.rows","rows","synthetic-checksum","committed-generated-rows","레코드 생성부터 마지막 동기 sink 반환까지")),
                List.of("rows","batchSize","seed","failBatch","measurement.mode"),"성능 비교가 아닌 합성 결과 검증. 실패 Run의 최종 summary는 비어 있을 수 있으며 성공한 배치의 timeline을 확인한다.")));
    }
}
