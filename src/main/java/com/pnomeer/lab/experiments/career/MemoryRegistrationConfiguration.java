package com.pnomeer.lab.experiments.career;

import com.pnomeer.lab.core.*;
import com.pnomeer.lab.registry.*;
import com.pnomeer.lab.runtime.*;
import com.pnomeer.lab.scenario.*;
import com.pnomeer.lab.storage.*;
import com.pnomeer.lab.worker.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static com.pnomeer.lab.registry.ParameterSpec.*;

@Configuration
@ConditionalOnProperty(name="lab.career.memory.enabled", havingValue="true")
public class MemoryRegistrationConfiguration {
    private static final Map<String,ParameterSpec> PARAMETERS=Map.of("variant",choice("BEFORE","AFTER","FAIL_AFTER_MEASUREMENT"),"rows",integer(1,5000,"rows"),"seed",integer(0,Integer.MAX_VALUE,"seed"));
    public static final ExperimentDescriptor BATCH=descriptor("batch-memory","Batch sampled heap",PARAMETERS);
    public static final ExperimentDescriptor EXCEL=descriptor("excel-memory","Excel sampled heap",PARAMETERS);
    public static final Map<String,String> METRICS=Map.of("memory.baselineBytes","bytes","memory.sampledPeakBytes","bytes","memory.sampledDeltaBytes","bytes",
            "memory.samples","samples","memory.elapsedNanos","ns","memory.heapLimitBytes","bytes","memory.processedRows","rows","memory.outputBytes","bytes");
    private static ExperimentDescriptor descriptor(String name,String title,Map<String,ParameterSpec> parameters) {
        return new ExperimentDescriptor("career.batch-excel."+name,title,"CAREER","NONE",parameters,List.of("SINGLE_PASS"));
    }
    public record Scenario(ScenarioDefinition definition,String hash) implements ExperimentScenario {
        public String scenarioId(){return definition.scenario().id();}
        public Map<String,String> parameters(){ var p=new TreeMap<String,String>(); definition.parameters().forEach((k,v)->p.put(k,v.toString())); return Map.copyOf(p); }
    }
    private ExperimentRegistration<Scenario> registration(ExperimentDescriptor descriptor,ScenarioDocuments documents) {
        return new ExperimentRegistration<>(descriptor,ignored->new Experiment<>() {
            public String type(){return descriptor.id();}
            public ExperimentSummary execute(Scenario scenario, Consumer<MetricPoint> sink){throw new IllegalStateException("Memory requires its container executor");}
        },(definition,input)->new Scenario(definition,documents.hash(definition)));
    }
    @Bean public ExperimentRegistration<Scenario> batchMemory(ScenarioDocuments documents){return registration(BATCH,documents);}
    @Bean public ExperimentRegistration<Scenario> excelMemory(ScenarioDocuments documents){return registration(EXCEL,documents);}
    @Bean public ExperimentModule memoryModule(){return new ExperimentModule(){
        public ModuleDescriptor module(){return new ModuleDescriptor("batch-excel","Batch / Excel memory","career");}
        public List<ExperimentDescriptor> experiments(){return List.of(BATCH,EXCEL);}
        public List<com.pnomeer.lab.metrics.MetricCatalog.Definition> metrics(){return METRICS.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e->new com.pnomeer.lab.metrics.MetricCatalog.Definition(e.getKey(),e.getValue(),"worker-run","RUN_FINAL",
                        "Sampled heap, not true peak/live set/RSS; 20ms plus boundary samples, no explicit GC; startup/seed/validation excluded; final value, never sum snapshots")).toList();}
    };}
    @Bean public ContainerWorkerRegistration batchMemoryWorker(StorageProperties storage,JdbcRunStore store,
            @Value("${lab.career.memory.container.image-id}") String image,@Value("${lab.career.memory.container.artifact-sha256}") String hash,
            @Value("${lab.career.memory.container.mysql-image-id}") String mysql,@Value("${lab.career.memory.recover-on-startup:false}") boolean recover)throws Exception {
        return worker(BATCH,storage,store,image,hash,mysql,recover);
    }
    @Bean public ContainerWorkerRegistration excelMemoryWorker(StorageProperties storage,JdbcRunStore store,
            @Value("${lab.career.memory.container.image-id}") String image,@Value("${lab.career.memory.container.artifact-sha256}") String hash,
            @Value("${lab.career.memory.container.mysql-image-id}") String mysql,@Value("${lab.career.memory.recover-on-startup:false}") boolean recover)throws Exception {
        return worker(EXCEL,storage,store,image,hash,mysql,recover);
    }
    private ContainerWorkerRegistration worker(ExperimentDescriptor descriptor,StorageProperties storage,JdbcRunStore store,String image,String hash,
            String dependency,boolean recover)throws Exception {
        Path root=Path.of(storage.root()).toAbsolutePath();
        String name=descriptor.id().substring("career.batch-excel.".length());
        Set<String> keys=METRICS.keySet();
        var binding=new ContainerWorkerRegistration("memory-"+name+"-worker",new ContainerWorkerManifest(image,hash,"batch-excel",Set.of(descriptor.id())),
                Files.createDirectories(root.resolve("worker-workspaces")),root,keys,scenario->{
                    var input=(Scenario)scenario; var parameters=WireJson.object();
                    input.definition().parameters().forEach((k,v)->{if(v instanceof Number n) parameters.put(k,n.longValue()); else parameters.put(k,v.toString());});
                    return new LocalJvmExecutor.Request(parameters,input.hash(),((Number)input.definition().parameters().get("seed")).longValue());
                },new BatchExcelContainerResources(dependency),()->{
                    try {
                        for(String expected:List.of(image,dependency)) if(!expected.equals(DockerContainerEngine.command("image","inspect",expected,"--format","{{.Id}}").trim()))
                            return new LocalJvmRegistration.Readiness("INCOMPATIBLE","CONTAINER_IMAGE_MISMATCH");
                        return new LocalJvmRegistration.Readiness("READY",null);
                    } catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return new LocalJvmRegistration.Readiness("UNAVAILABLE","CONTAINER_ENGINE_OR_IMAGE_UNAVAILABLE");}
                },descriptor.parameters().get("variant").values());
        if(recover) org.slf4j.LoggerFactory.getLogger(getClass()).info("stage=container_startup_recovery registration_id={} result={}",binding.id(),binding.recover(store));
        return binding;
    }
}
