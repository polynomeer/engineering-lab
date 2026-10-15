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
@ConditionalOnProperty(name="lab.career.equity.enabled", havingValue="true")
public class EquityRegistrationConfiguration {
    public static final ExperimentDescriptor DELETION = descriptor("deletion", "Equity deletion", Map.of("variant",choice("BEFORE","AFTER"),"rows",integer(1,50000,"rows")));
    public static final ExperimentDescriptor REDIS = descriptor("redis-lock", "Redis ownership lock", Map.of("variant",choice("BEFORE","AFTER")));
    public static final ExperimentDescriptor REGISTRATION = descriptor("registration", "Equity registration", Map.of("variant",choice("BEFORE","AFTER","INVALID_ROW"),
            "rows",integer(1,10000,"rows"),"chunkSize",integer(1,1000,"rows"),"workers",integer(1,8,"threads"),"maxInFlight",integer(1,16,"chunks")));
    public static final Map<String,String> METRICS = Map.of(
            "deletion.elapsedNanos","ns", "deletion.maxSingleStepMs","ms", "deletion.deletedRows","rows", "deletion.chunks","chunks",
            "registration.elapsedNanos","ns", "registration.storedRows","rows", "registration.maxInFlightChunks","chunks",
            "registration.backpressureWaits","waits", "registration.backpressureNanos","ns");
    private static ExperimentDescriptor descriptor(String name,String title,Map<String,ParameterSpec> parameters) {
        return new ExperimentDescriptor("career.equity-system."+name,title,"CAREER","NONE",parameters,List.of("SINGLE_PASS"));
    }
    public record Scenario(ScenarioDefinition definition,String hash) implements ExperimentScenario {
        public String scenarioId(){return definition.scenario().id();}
        public Map<String,String> parameters(){ var p=new TreeMap<String,String>(); definition.parameters().forEach((k,v)->p.put(k,v.toString())); return Map.copyOf(p); }
    }
    private ExperimentRegistration<Scenario> registration(ExperimentDescriptor descriptor,ScenarioDocuments documents) {
        return new ExperimentRegistration<>(descriptor,ignored->new Experiment<>() {
            public String type(){return descriptor.id();}
            public ExperimentSummary execute(Scenario scenario, Consumer<MetricPoint> sink){throw new IllegalStateException("Equity requires its container executor");}
        },(definition,input)->new Scenario(definition,documents.hash(definition)));
    }
    @Bean public ExperimentRegistration<Scenario> equityDeletion(ScenarioDocuments documents){return registration(DELETION,documents);}
    @Bean public ExperimentRegistration<Scenario> equityRedis(ScenarioDocuments documents){return registration(REDIS,documents);}
    @Bean public ExperimentRegistration<Scenario> equityRegistration(ScenarioDocuments documents){return registration(REGISTRATION,documents);}
    @Bean public ExperimentModule equityModule(){return new ExperimentModule(){
        public ModuleDescriptor module(){return new ModuleDescriptor("equity-system","Equity system","career");}
        public List<ExperimentDescriptor> experiments(){return List.of(DELETION,REDIS,REGISTRATION);}
        public List<com.pnomeer.lab.metrics.MetricCatalog.Definition> metrics(){return METRICS.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e->new com.pnomeer.lab.metrics.MetricCatalog.Definition(e.getKey(),e.getValue(),"worker-run","RUN_FINAL",
                        e.getKey().equals("deletion.maxSingleStepMs") ? "Maximum JDBC deletion step including commit; transaction-duration proxy, not measured lock occupancy"
                        : "Synthetic workload; final measured value, never sum snapshots; registration includes a synthetic 2ms row wait")).toList();}
    };}
    @Bean public ContainerWorkerRegistration equityDeletionWorker(StorageProperties storage,JdbcRunStore store,
            @Value("${lab.career.equity.container.image-id}") String image,@Value("${lab.career.equity.container.artifact-sha256}") String hash,
            @Value("${lab.career.equity.container.mysql-image-id}") String mysql,@Value("${lab.career.equity.recover-on-startup:false}") boolean recover)throws Exception {
        return worker(DELETION,storage,store,image,hash,mysql,false,recover);
    }
    @Bean public ContainerWorkerRegistration equityRegistrationWorker(StorageProperties storage,JdbcRunStore store,
            @Value("${lab.career.equity.container.image-id}") String image,@Value("${lab.career.equity.container.artifact-sha256}") String hash,
            @Value("${lab.career.equity.container.mysql-image-id}") String mysql,@Value("${lab.career.equity.recover-on-startup:false}") boolean recover)throws Exception {
        return worker(REGISTRATION,storage,store,image,hash,mysql,false,recover);
    }
    @Bean public ContainerWorkerRegistration equityRedisWorker(StorageProperties storage,JdbcRunStore store,
            @Value("${lab.career.equity.container.image-id}") String image,@Value("${lab.career.equity.container.artifact-sha256}") String hash,
            @Value("${lab.career.equity.container.redis-image-id}") String redis,@Value("${lab.career.equity.recover-on-startup:false}") boolean recover)throws Exception {
        return worker(REDIS,storage,store,image,hash,redis,true,recover);
    }
    private ContainerWorkerRegistration worker(ExperimentDescriptor descriptor,StorageProperties storage,JdbcRunStore store,String image,String hash,
            String dependency,boolean redis,boolean recover)throws Exception {
        Path root=Path.of(storage.root()).toAbsolutePath();
        String name=descriptor.id().substring("career.equity-system.".length());
        Set<String> keys=METRICS.keySet().stream().filter(k->k.startsWith(name+".")).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var binding=new ContainerWorkerRegistration("equity-"+name+"-worker",new ContainerWorkerManifest(image,hash,"equity-system",Set.of(descriptor.id())),
                Files.createDirectories(root.resolve("worker-workspaces")),root,keys,scenario->{
                    var input=(Scenario)scenario; var parameters=WireJson.object();
                    input.definition().parameters().forEach((k,v)->{if(v instanceof Number n) parameters.put(k,n.longValue()); else parameters.put(k,v.toString());});
                    return new LocalJvmExecutor.Request(parameters,input.hash(),0);
                },new EquityContainerResources(dependency,redis),()->{
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
