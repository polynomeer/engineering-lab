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
@ConditionalOnProperty(name="lab.career.mds.enabled", havingValue="true")
public class MdsRegistrationConfiguration {
    public static final ExperimentDescriptor GUARD=descriptor("settlement-guard","Settlement guard",List.of("BEFORE","AFTER","TTL_RECOVERY"));
    public static final ExperimentDescriptor IDEMPOTENCY=descriptor("idempotency","Message idempotency",List.of("SEQUENTIAL","CONCURRENT","RETRY_AFTER_FAILURE","PROCESSING_FAILURE"));
    private static ExperimentDescriptor descriptor(String name,String title,List<String> variants) {
        return new ExperimentDescriptor("career.mds-distribution."+name,title,"CAREER","NONE",Map.of("variant",choice(variants.toArray(String[]::new))),List.of("SINGLE_PASS"));
    }
    public record Scenario(ScenarioDefinition definition,String hash) implements ExperimentScenario {
        public String scenarioId(){return definition.scenario().id();}
        public Map<String,String> parameters(){ var p=new TreeMap<String,String>(); definition.parameters().forEach((k,v)->p.put(k,v.toString())); return Map.copyOf(p); }
    }
    private ExperimentRegistration<Scenario> registration(ExperimentDescriptor descriptor,ScenarioDocuments documents) {
        return new ExperimentRegistration<>(descriptor,ignored->new Experiment<>() {
            public String type(){return descriptor.id();}
            public ExperimentSummary execute(Scenario scenario, Consumer<MetricPoint> sink){throw new IllegalStateException("MDS requires its container executor");}
        },(definition,input)->new Scenario(definition,documents.hash(definition)));
    }
    @Bean public ExperimentRegistration<Scenario> settlementGuard(ScenarioDocuments documents){return registration(GUARD,documents);}
    @Bean public ExperimentRegistration<Scenario> idempotency(ScenarioDocuments documents){return registration(IDEMPOTENCY,documents);}
    @Bean public ExperimentModule mdsModule(){return new ExperimentModule(){
        public ModuleDescriptor module(){return new ModuleDescriptor("mds-distribution","MDS distribution","career");}
        public List<ExperimentDescriptor> experiments(){return List.of(GUARD,IDEMPOTENCY);}
    };}
    @Bean public ContainerWorkerRegistration settlementGuardWorker(StorageProperties storage,JdbcRunStore store,
            @Value("${lab.career.mds.container.image-id}") String image,@Value("${lab.career.mds.container.artifact-sha256}") String hash,
            @Value("${lab.career.mds.container.mysql-image-id}") String mysql,@Value("${lab.career.mds.recover-on-startup:false}") boolean recover)throws Exception {
        return worker(GUARD,storage,store,image,hash,mysql,recover);
    }
    @Bean public ContainerWorkerRegistration idempotencyWorker(StorageProperties storage,JdbcRunStore store,
            @Value("${lab.career.mds.container.image-id}") String image,@Value("${lab.career.mds.container.artifact-sha256}") String hash,
            @Value("${lab.career.mds.container.mysql-image-id}") String mysql,@Value("${lab.career.mds.recover-on-startup:false}") boolean recover)throws Exception {
        return worker(IDEMPOTENCY,storage,store,image,hash,mysql,recover);
    }
    private ContainerWorkerRegistration worker(ExperimentDescriptor descriptor,StorageProperties storage,JdbcRunStore store,String image,String hash,
            String dependency,boolean recover)throws Exception {
        Path root=Path.of(storage.root()).toAbsolutePath();
        String name=descriptor.id().substring("career.mds-distribution.".length());
        Set<String> keys=Set.of();
        var binding=new ContainerWorkerRegistration("mds-"+name+"-worker",new ContainerWorkerManifest(image,hash,"mds-distribution",Set.of(descriptor.id())),
                Files.createDirectories(root.resolve("worker-workspaces")),root,keys,scenario->{
                    var input=(Scenario)scenario; var parameters=WireJson.object();
                    input.definition().parameters().forEach((k,v)->{if(v instanceof Number n) parameters.put(k,n.longValue()); else parameters.put(k,v.toString());});
                    return new LocalJvmExecutor.Request(parameters,input.hash(),0);
                },new MdsContainerResources(dependency),()->{
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
