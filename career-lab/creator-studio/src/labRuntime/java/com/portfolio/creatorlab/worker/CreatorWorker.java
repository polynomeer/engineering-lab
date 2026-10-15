package com.portfolio.creatorlab.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.worker.*;
import com.portfolio.creatorlab.CreatorLabApplication;
import com.portfolio.creatorlab.domain.*;
import com.portfolio.creatorlab.repository.*;
import com.portfolio.creatorlab.session.*;
import com.portfolio.creatorlab.onboarding.*;
import com.portfolio.creatorlab.integration.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Existing services with bounded synthetic fixtures; external integrations are recording stubs only. */
public final class CreatorWorker {
    private final WorkerProtocol.Writer writer;
    private final ObjectNode report=WireJson.object().put("provenance","FACT-based mechanisms; proposed session design and hypothesized mapping failure; NEW-DESIGN fixtures; external clients are stubs");
    private boolean failed, measuring;
    private CreatorWorker(WorkerProtocol.Writer writer){this.writer=writer;report.putArray("checks");}
    public static void validate(String experiment,JsonNode p){
        Set<String> variants=switch(experiment){
            case "career.creator-studio.session-rotation" -> Set.of("BEFORE","AFTER","CONCURRENT");
            case "career.creator-studio.mapping-integrity" -> Set.of("BEFORE","AFTER","TRANSFER");
            case "career.creator-studio.post-commit" -> Set.of("COMMIT","ROLLBACK","RETRY","EXHAUSTED");
            default -> throw new IllegalArgumentException("Unsupported experiment");
        };
        if(p.size()!=1 || !p.path("variant").isTextual() || !variants.contains(p.path("variant").asText()))throw new IllegalArgumentException("Invalid parameters");
    }
    private void check(String id,boolean pass,String expected,String observed)throws Exception {
        if(!measuring){writer.emit("PHASE",WireJson.object().put("phase","MEASURING"));measuring=true;}
        var row=WireJson.object().put("checkId",id).put("status",pass?"PASS":"FAIL").put("expected",expected).put("observed",observed)
                .put("description","Synthetic fixture; PASS means the stated expectation matched, not historical production proof");
        writer.emit("CHECK",row);report.withArray("checks").add(row);failed|=!pass;
    }
    private void execute(String experiment,JsonNode p,String runId)throws Exception {
        String variant=p.path("variant").asText();report.put("experimentId",experiment).put("variant",variant);report.set("parameters",p.deepCopy());
        report.put("externalIntegration","recording stubs only; no external network calls; no outbox/crash durability guarantee");
        if(experiment.endsWith("session-rotation")){sessions(variant);return;}
        if(!"creator-db".equals(System.getenv("LAB_CREATOR_DB_HOST")) || !System.getenv().getOrDefault("LAB_CREATOR_DB_PASSWORD","").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Controller database endpoint required");
        try(var app=new SpringApplicationBuilder(CreatorLabApplication.class).web(WebApplicationType.NONE).run(
                "--spring.main.banner-mode=off","--logging.level.root=WARN","--lab.creator.worker.stub.enabled=true","--lab.creator.worker.stub.mode="+variant,
                "--spring.datasource.url=jdbc:mysql://creator-db:3306/creator_lab?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=30000",
                "--spring.datasource.username=lab","--spring.datasource.password="+System.getenv("LAB_CREATOR_DB_PASSWORD"),
                "--spring.datasource.hikari.maximum-pool-size=4","--spring.datasource.hikari.minimum-idle=1","--spring.datasource.hikari.connection-timeout=10000")) {
            if(experiment.endsWith("mapping-integrity"))mapping(app,variant);else postCommit(app,variant);
        }
    }
    private void sessions(String variant)throws Exception {
        if(!"creator-db".equals(System.getenv("LAB_CREATOR_REDIS_HOST")))throw new IllegalArgumentException("Controller Redis endpoint required");
        var factory=new LettuceConnectionFactory("creator-db",6379);factory.afterPropertiesSet();factory.start();
        try {
            var redis=new StringRedisTemplate(factory);boolean before=variant.equals("BEFORE");
            SessionManager manager=before?new LegacySessionManager(redis,Duration.ofSeconds(30),Duration.ofSeconds(60)):new ImprovedSessionManager(redis,Duration.ofSeconds(30),Duration.ofSeconds(60));
            var original=manager.issue(1001L,"device-A");
            check("issued-session",manager.lookup(original.accessToken()).isPresent(),"original valid","valid="+manager.lookup(original.accessToken()).isPresent());
            TokenPair rotated;
            if(variant.equals("CONCURRENT")){
                var executor=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
                try {
                    Callable<TokenPair> call=()->{ready.countDown();if(!go.await(5,TimeUnit.SECONDS))throw new TimeoutException("gate");try{return manager.refresh(original.refreshToken(),"device-A");}catch(InvalidTokenException expected){return null;}};
                    var a=executor.submit(call);var b=executor.submit(call);if(!ready.await(5,TimeUnit.SECONDS))throw new TimeoutException("ready");go.countDown();
                    var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);
                    int winners=(first==null?0:1)+(second==null?0:1);
                    check("one-refresh-winner",winners==1,"one accepted and one rejected",Integer.toString(winners));rotated=first==null?second:first;
                } finally {go.countDown();executor.shutdownNow();if(!executor.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("refresh tasks did not stop");}
            } else rotated=manager.refresh(original.refreshToken(),"device-A");
            boolean old=manager.lookup(original.accessToken()).isPresent();
            check("old-token-policy",before?old:!old,before?"old token still accepted (problem)":"old token rejected","oldAccepted="+old);
            check("new-token-valid",rotated!=null && manager.lookup(rotated.accessToken()).filter(d->d.memberId()==1001L && d.deviceId().equals("device-A")).isPresent(),"new token maps to expected member/device","valid="+(rotated!=null && manager.lookup(rotated.accessToken()).isPresent()));
            if(variant.equals("AFTER")){
                boolean rejected=false;try{manager.refresh(original.refreshToken(),"device-A");}catch(InvalidTokenException expected){rejected=true;}
                check("refresh-reuse",rejected,"used refresh rejected","rejected="+rejected);
                boolean wrong=false;try{manager.refresh(rotated.refreshToken(),"wrong-device");}catch(InvalidTokenException expected){wrong=true;}
                check("device-binding",wrong,"wrong device rejected without consuming token","rejected="+wrong);
                var latest=manager.refresh(rotated.refreshToken(),"device-A");
                check("rotation-chain",manager.lookup(rotated.accessToken()).isEmpty() && manager.lookup(latest.accessToken()).isPresent(),"only latest access valid","previousValid="+manager.lookup(rotated.accessToken()).isPresent()+", latestValid="+manager.lookup(latest.accessToken()).isPresent());
            }
        } finally {factory.destroy();}
    }
    private void mapping(ConfigurableApplicationContext app,String variant)throws Exception {
        var members=app.getBean(MemberRepository.class);var producers=app.getBean(ProducerRepository.class);var creators=app.getBean(CreatorRepository.class);
        var programs=app.getBean(ProgramRepository.class);var history=app.getBean(ProducerMappingHistoryRepository.class);var onboarding=app.getBean(CreatorOnboardingService.class);
        var member=members.save(new Member("selected@example.invalid"));var creator=onboarding.joinAsNewCreator(member.getId(),"source","selected");
        long source=creator.getProducerId();var own=programs.save(new Program(source,creator.getId(),"selected-program"));
        var otherMember=members.save(new Member("other@example.invalid"));var other=creators.save(new Creator(otherMember.getId(),source,"other"));
        var untouched=programs.save(new Program(source,other.getId(),"other-program"));var target=producers.save(new Producer("target"));
        if(variant.equals("BEFORE")){
            var remapped=app.getBean(LegacyProducerCreationService.class).createProducerAndRemapCreator(member.getId(),"legacy-new","selected");
            check("mapping-overwritten",!remapped.getProducerId().equals(source),"new producer disconnects old content (hypothesis)","mappingChanged="+!remapped.getProducerId().equals(source));
            check("content-not-deleted",programs.findById(own.getId()).orElseThrow().getProducerId().equals(source) && programs.findByProducerId(remapped.getProducerId()).isEmpty(),"original program remains at old producer","programCount="+programs.count());return;
        }
        long count=producers.count();boolean duplicate=false;try{onboarding.joinAsNewCreator(member.getId(),"duplicate","selected");}catch(CreatorAlreadyExistsException expected){duplicate=true;}
        check("duplicate-onboarding",duplicate && producers.count()==count,"rejected; no new producer","rejected="+duplicate+", producers="+producers.count());
        var admin=app.getBean(ProducerMappingAdminService.class);boolean blocked=false;try{admin.changeProducer(creator.getId(),target.getId(),member.getId(),"blocked",false);}catch(ContentTransferRequiredException expected){blocked=true;}
        check("transfer-confirmation",blocked,"content requires explicit confirmation","blocked="+blocked);
        check("blocked-state",creators.findById(creator.getId()).orElseThrow().getProducerId().equals(source) && history.findByCreatorId(creator.getId()).isEmpty() && programs.findById(own.getId()).orElseThrow().getProducerId().equals(source),"mapping/content unchanged, history empty","history="+history.findByCreatorId(creator.getId()).size());
        if(variant.equals("TRANSFER")){
            admin.changeProducer(creator.getId(),target.getId(),member.getId(),"explicit transfer",true);
            check("scoped-transfer",creators.findById(creator.getId()).orElseThrow().getProducerId().equals(target.getId()) && programs.findById(own.getId()).orElseThrow().getProducerId().equals(target.getId()) && programs.findById(untouched.getId()).orElseThrow().getProducerId().equals(source),"selected creator/program moved; other creator program retained","selectedMoved="+programs.findById(own.getId()).orElseThrow().getProducerId().equals(target.getId())+", otherRetained="+programs.findById(untouched.getId()).orElseThrow().getProducerId().equals(source));
            var entries=history.findByCreatorId(creator.getId());
            check("mapping-history",entries.size()==1 && entries.getFirst().getFromProducerId().equals(source) && entries.getFirst().getToProducerId().equals(target.getId()) && entries.getFirst().getReason().equals("explicit transfer"),"one from/to/reason history record","history="+entries.size());
        }
    }
    private void postCommit(ConfigurableApplicationContext app,String variant)throws Exception {
        var programs=app.getBean(ProgramRepository.class);var recorder=app.getBean(Recorders.class);
        var member=app.getBean(MemberRepository.class).save(new Member("post@example.invalid"));
        var creator=app.getBean(CreatorOnboardingService.class).joinAsNewCreator(member.getId(),"post-channel","post");
        boolean rollback=variant.equals("ROLLBACK");
        var tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        Long id=tx.execute(status->{
            var program=app.getBean(ProgramCreationService.class).createProgram(creator.getProducerId(),creator.getId(),"post-program");
            try{check("precommit-quiet",recorder.braze.get()==0 && recorder.mixpanel.get()==0,"no client calls before commit","braze="+recorder.braze.get()+", mixpanel="+recorder.mixpanel.get());}catch(Exception e){throw new IllegalStateException(e);}
            if(rollback)status.setRollbackOnly();return program.getId();
        });
        // Once publication/transaction completion returns, drain the exact executor to observe all attempts.
        var executor=app.getBean("externalIntegrationExecutor",ThreadPoolTaskExecutor.class).getThreadPoolExecutor();executor.shutdown();
        if(!executor.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("integration executor did not drain");
        check("transaction-result",programs.existsById(id)!=rollback,rollback?"program rolled back":"program committed","exists="+programs.existsById(id));
        int expected=rollback?0:(variant.equals("RETRY") || variant.equals("EXHAUSTED")?3:1);
        check("listener-attempts",recorder.braze.get()==expected && recorder.mixpanel.get()==(rollback?0:1),"braze="+expected+", mixpanel="+(rollback?0:1),"braze="+recorder.braze.get()+", mixpanel="+recorder.mixpanel.get());
        check("committed-visibility",recorder.committed.get() && (rollback?recorder.last.get()==null:Objects.equals(recorder.last.get(),new ContentChangedEvent(ContentType.PROGRAM,id,ChangeType.CREATED))),rollback?"no callback":"every callback sees committed row and matching event","committedVisible="+recorder.committed.get()+", eventRecorded="+(recorder.last.get()!=null));
        check("delivery-completed",rollback || recorder.brazeSuccess.get()==1,"rollback: no delivery; commit: both clients succeed","brazeSuccess="+recorder.brazeSuccess.get()+", mixpanelSuccess="+recorder.mixpanel.get()+"; stub only, exhausted failure does not undo commit");
    }
    static final class Recorders {
        final AtomicInteger braze=new AtomicInteger(),mixpanel=new AtomicInteger(),brazeSuccess=new AtomicInteger();
        final AtomicBoolean committed=new AtomicBoolean(true);final AtomicReference<ContentChangedEvent> last=new AtomicReference<>();
        final ProgramRepository programs;final String mode;
        Recorders(ProgramRepository programs,String mode){this.programs=programs;this.mode=mode;}
        void observe(ContentChangedEvent event){last.set(event);if(!programs.existsById(event.contentId()))committed.set(false);}
        void braze(ContentChangedEvent event){observe(event);int n=braze.incrementAndGet();if(mode.equals("EXHAUSTED") || mode.equals("RETRY") && n<3)throw new ExternalIntegrationException("Synthetic stub failure");brazeSuccess.incrementAndGet();}
        void mixpanel(ContentChangedEvent event){observe(event);mixpanel.incrementAndGet();}
    }
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="lab.creator.worker.stub.enabled",havingValue="true")
    static class StubConfiguration {
        @Bean Recorders recorders(ProgramRepository programs,@Value("${lab.creator.worker.stub.mode}") String mode){return new Recorders(programs,mode);}
        @Bean @Primary BrazeClient workerBraze(Recorders recorders){return recorders::braze;}
        @Bean @Primary MixpanelClient workerMixpanel(Recorders recorders){return recorders::mixpanel;}
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Unsupported command");
        OutputStream protocol = System.out; System.setOut(System.err);
        var command = WorkerProtocol.readCommand(System.in, ProtocolLimits.DEFAULT);
        String experiment = command.path("experimentId").asText();
        var parameters = command.path("parameters");
        validate(experiment, parameters);
        String variant = parameters.path("variant").asText();
        long remaining = command.path("deadlineEpochMs").asLong() - System.currentTimeMillis();
        if (remaining <= 0 || remaining > 900000) throw new IllegalArgumentException("Invalid deadline");
        Path workspace = Path.of(command.path("workspace").asText());
        if (!workspace.isAbsolute() || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Invalid workspace");
        // Controller owns MySQL; worker termination leaves cleanup to the recorded boundary.
        ParentConnection.watch(System.in, () -> Runtime.getRuntime().halt(20));
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(remaining); Runtime.getRuntime().halt(21); } catch (InterruptedException ignored) {}
        }, "creator-deadline"); deadline.setDaemon(true); deadline.start();
        var identity = new WorkerProtocol.Identity(command.path("runId").asText(), "creator-studio", experiment,
                command.path("definitionSha256").asText(), hash(Path.of(System.getProperty("java.class.path"))));
        var writer = new WorkerProtocol.Writer(protocol, identity, ProtocolLimits.DEFAULT);
        ObjectNode hello = WireJson.object().put("moduleId", identity.moduleId()).put("experimentId", experiment)
                .put("definitionSha256", identity.definitionSha256()).put("artifactSha256", identity.artifactSha256());
        hello.set("environment", WireJson.object().put("javaVersion", System.getProperty("java.version")).put("javaVendor", System.getProperty("java.vendor"))
                .put("vmName", System.getProperty("java.vm.name")).put("osName", System.getProperty("os.name"))
                .put("osVersion", System.getProperty("os.version")).put("osArch", System.getProperty("os.arch"))
                .put("availableProcessors", Runtime.getRuntime().availableProcessors()).put("maxHeapBytes", Runtime.getRuntime().maxMemory()));
        hello.set("git", WireJson.object().putNull("commit").putNull("dirty").put("error", "Build Git provenance not embedded"));
        writer.emit("HELLO", hello); writer.emit("PHASE", WireJson.object().put("phase", "PREPARING"));
        var worker = new CreatorWorker(writer);
        try {
            worker.execute(experiment, parameters, command.path("runId").asText());
        } catch (Exception e) {
            worker.failed = true;
            worker.check("execution",false,"completed","execution failed: " + e.getClass().getSimpleName());
            System.err.println("job_id=" + identity.runId() + " stage=creator reason=EXECUTION_FAILED"); e.printStackTrace(System.err);
        }
        try {
            writer.emit("PHASE", WireJson.object().put("phase","FINALIZING"));
            Path file=workspace.resolve("creator-report.json"); Files.write(file,WireJson.encode(worker.report),StandardOpenOption.CREATE_NEW);
            writer.emit("ARTIFACT",WireJson.object().put("path",file.getFileName().toString()).put("kind","REPORT").put("sizeBytes",Files.size(file)).put("sha256",hash(file)));
            writer.result(worker.failed ? "FAILED" : "SUCCEEDED",worker.failed ? "EXPECTATION_NOT_MET" : "",WireJson.object().put("variant",variant));
        } finally { deadline.interrupt(); }
    }
    private static String hash(Path file) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(file)){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
