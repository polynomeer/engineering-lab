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
@ConditionalOnProperty(name="lab.career.creator.enabled", havingValue="true")
public class CreatorRegistrationConfiguration {
    public static final ExperimentDescriptor SESSION=descriptor("session-rotation","Session rotation",List.of("BEFORE","AFTER","CONCURRENT"));
    public static final ExperimentDescriptor MAPPING=descriptor("mapping-integrity","Mapping integrity",List.of("BEFORE","AFTER","TRANSFER"));
    public static final ExperimentDescriptor POST_COMMIT=descriptor("post-commit","Post-commit integration",List.of("COMMIT","ROLLBACK","RETRY","EXHAUSTED"));
    private static ExperimentDescriptor descriptor(String name,String title,List<String> variants) {
        return new ExperimentDescriptor("career.creator-studio."+name,title,"CAREER","NONE",Map.of("variant",choice(variants.toArray(String[]::new))),List.of("SINGLE_PASS"));
    }
    public record Scenario(ScenarioDefinition definition,String hash) implements ExperimentScenario {
        public String scenarioId(){return definition.scenario().id();}
        public Map<String,String> parameters(){ var p=new TreeMap<String,String>(); definition.parameters().forEach((k,v)->p.put(k,v.toString())); return Map.copyOf(p); }
    }
    private ExperimentRegistration<Scenario> registration(ExperimentDescriptor descriptor,ScenarioDocuments documents) {
        return new ExperimentRegistration<>(descriptor,ignored->new Experiment<>() {
            public String type(){return descriptor.id();}
            public ExperimentSummary execute(Scenario scenario, Consumer<MetricPoint> sink){throw new IllegalStateException("Creator requires its container executor");}
        },(definition,input)->new Scenario(definition,documents.hash(definition)));
    }
    @Bean public ExperimentRegistration<Scenario> sessionRotation(ScenarioDocuments documents){return registration(SESSION,documents);}
    @Bean public ExperimentRegistration<Scenario> mappingIntegrity(ScenarioDocuments documents){return registration(MAPPING,documents);}
    @Bean public ExperimentRegistration<Scenario> postCommit(ScenarioDocuments documents){return registration(POST_COMMIT,documents);}
    @Bean public ExperimentModule creatorModule(){return new ExperimentModule(){
        public ModuleDescriptor module(){return new ModuleDescriptor("creator-studio","Creator studio","career");}
        public List<ExperimentDescriptor> experiments(){return List.of(SESSION,MAPPING,POST_COMMIT);}
    };}
    @Bean public ContainerWorkerRegistration sessionRotationWorker(StorageProperties storage,JdbcRunStore store,
            @Value("${lab.career.creator.container.image-id}") String image,@Value("${lab.career.creator.container.artifact-sha256}") String hash,
            @Value("${lab.career.creator.container.redis-image-id}") String dependency,@Value("${lab.career.creator.recover-on-startup:false}") boolean recover)throws Exception {
        return worker(SESSION,storage,store,image,hash,dependency,recover);
    }
    @Bean public ContainerWorkerRegistration mappingIntegrityWorker(StorageProperties storage,JdbcRunStore store,
            @Value("${lab.career.creator.container.image-id}") String image,@Value("${lab.career.creator.container.artifact-sha256}") String hash,
            @Value("${lab.career.creator.container.mysql-image-id}") String dependency,@Value("${lab.career.creator.recover-on-startup:false}") boolean recover)throws Exception {
        return worker(MAPPING,storage,store,image,hash,dependency,recover);
    }
    @Bean public ContainerWorkerRegistration postCommitWorker(StorageProperties storage,JdbcRunStore store,
            @Value("${lab.career.creator.container.image-id}") String image,@Value("${lab.career.creator.container.artifact-sha256}") String hash,
            @Value("${lab.career.creator.container.mysql-image-id}") String dependency,@Value("${lab.career.creator.recover-on-startup:false}") boolean recover)throws Exception {
        return worker(POST_COMMIT,storage,store,image,hash,dependency,recover);
    }
    private ContainerWorkerRegistration worker(ExperimentDescriptor descriptor,StorageProperties storage,JdbcRunStore store,String image,String hash,
            String dependency,boolean recover)throws Exception {
        Path root=Path.of(storage.root()).toAbsolutePath();
        String name=descriptor.id().substring("career.creator-studio.".length());
        Set<String> keys=Set.of();
        var binding=new ContainerWorkerRegistration("creator-"+name+"-worker",new ContainerWorkerManifest(image,hash,"creator-studio",Set.of(descriptor.id())),
                Files.createDirectories(root.resolve("worker-workspaces")),root,keys,scenario->{
                    var input=(Scenario)scenario; var parameters=WireJson.object();
                    input.definition().parameters().forEach((k,v)->{if(v instanceof Number n) parameters.put(k,n.longValue()); else parameters.put(k,v.toString());});
                    return new LocalJvmExecutor.Request(parameters,input.hash(),0);
                },new CreatorContainerResources(dependency,descriptor.id().endsWith("session-rotation")),()->{
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
