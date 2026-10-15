package com.portfolio.mdslab.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.worker.*;
import com.portfolio.mdslab.MdsLabApplication;
import com.portfolio.mdslab.domain.*;
import com.portfolio.mdslab.repository.*;
import com.portfolio.mdslab.messaging.*;
import com.portfolio.mdslab.settlement.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** FACT-based services with NEW-DESIGN bounded fixtures; direct delivery, not real SQS. */
public final class MdsWorker {
    private final WorkerProtocol.Writer writer;
    private final ObjectNode report=WireJson.object().put("provenance","FACT-based mechanisms; NEW-DESIGN fixtures; no SQS or exactly-once crash guarantee");
    private boolean failed, measuring;
    private MdsWorker(WorkerProtocol.Writer writer){this.writer=writer;report.putArray("checks");}
    public static void validate(String experiment,JsonNode p){
        Set<String> variants=switch(experiment){
            case "career.mds-distribution.settlement-guard" -> Set.of("BEFORE","AFTER","TTL_RECOVERY");
            case "career.mds-distribution.idempotency" -> Set.of("SEQUENTIAL","CONCURRENT","RETRY_AFTER_FAILURE","PROCESSING_FAILURE");
            default -> throw new IllegalArgumentException("Unsupported experiment");
        };
        if(p.size()!=1 || !p.path("variant").isTextual() || !variants.contains(p.path("variant").asText()))throw new IllegalArgumentException("Invalid parameters");
    }
    private void check(String id,boolean pass,String expected,String observed)throws Exception {
        if(!measuring){writer.emit("PHASE",WireJson.object().put("phase","MEASURING"));measuring=true;}
        var row=WireJson.object().put("checkId",id).put("status",pass?"PASS":"FAIL").put("expected",expected).put("observed",observed)
                .put("description","Bounded synthetic fixture using existing services; PASS means the stated expectation matched");
        writer.emit("CHECK",row);report.withArray("checks").add(row);failed|=!pass;
    }
    private void execute(String experiment,JsonNode p,String runId)throws Exception {
        String variant=p.path("variant").asText();report.put("experimentId",experiment).put("variant",variant);report.set("parameters",p.deepCopy());
        report.put("delivery","direct listener invocation; no SQS, visibility timeout, ACK or DLQ validation")
                .put("limits","two concurrent deliveries; at most two processing attempts; TTL 2000ms, wait at most 8000ms");
        if(!"mds-db".equals(System.getenv("LAB_MDS_DB_HOST")) || !System.getenv().getOrDefault("LAB_MDS_DB_PASSWORD","").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Controller database endpoint required");
        try(var app=new SpringApplicationBuilder(MdsLabApplication.class).web(WebApplicationType.NONE).run(
                "--spring.main.banner-mode=off","--logging.level.root=WARN",
                "--spring.datasource.url=jdbc:mysql://mds-db:3306/mds_lab?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=30000",
                "--spring.datasource.username=lab","--spring.datasource.password="+System.getenv("LAB_MDS_DB_PASSWORD"),
                "--spring.datasource.hikari.maximum-pool-size=4","--spring.datasource.hikari.minimum-idle=1","--spring.datasource.hikari.connection-timeout=10000")) {
            if(experiment.endsWith("settlement-guard"))guard(app,variant,runId);else messages(app,variant,runId);
        }
    }
    private void guard(ConfigurableApplicationContext app,String variant,String runId)throws Exception {
        var albums=app.getBean(AlbumRepository.class);var locks=app.getBean(SettlementLockRepository.class);
        var batch=app.getBean(SettlementBatchService.class);var metadata=app.getBean(AlbumMetadataService.class);
        long id=albums.save(new Album("original")).getId();var type=SettlementTargetType.ALBUM;
        boolean ttl=variant.equals("TTL_RECOVERY");
        check("lock-acquired",batch.acquireLock(type,id,runId,ttl?Duration.ofSeconds(2):Duration.ofSeconds(30)),"acquired","owner="+runId);
        if(variant.equals("BEFORE")){
            metadata.updateMetadataUnguarded(id,"changed");
            check("unguarded-write",albums.findById(id).orElseThrow().getTitle().equals("changed"),"changed while active (problem reproduced)",albums.findById(id).orElseThrow().getTitle());
        } else {
            boolean blocked=false;try{metadata.updateMetadataGuarded(id,"forbidden");}catch(SettlementInProgressException expected){blocked=true;}
            check("active-guard",blocked && albums.findById(id).orElseThrow().getTitle().equals("original"),"blocked; original retained","blocked="+blocked+", title="+albums.findById(id).orElseThrow().getTitle());
        }
        if(ttl){
            long deadline=System.nanoTime()+Duration.ofSeconds(8).toNanos();
            while(locks.findByTargetTypeAndTargetId(type,id).orElseThrow().isActiveConsidering(LocalDateTime.now()) && System.nanoTime()<deadline)Thread.sleep(25);
            var expired=locks.findByTargetTypeAndTargetId(type,id).orElseThrow();
            check("ttl-expired",!expired.isActiveConsidering(LocalDateTime.now()),"expired without release; lazy evaluation","status="+expired.getStatus()+", expiresAt="+expired.getExpiresAt());
            metadata.updateMetadataGuarded(id,"after-ttl");
            check("expired-write",albums.findById(id).orElseThrow().getTitle().equals("after-ttl"),"after-ttl",albums.findById(id).orElseThrow().getTitle());
            String successor=runId+"-next";
            check("successor-acquired",batch.acquireLock(type,id,successor,Duration.ofSeconds(30)),"new owner acquires expired row","owner="+successor);
            boolean stale=batch.releaseLock(type,id,runId);var current=locks.findByTargetTypeAndTargetId(type,id).orElseThrow();
            check("stale-release",!stale && successor.equals(current.getLockedBy()) && current.isActiveConsidering(LocalDateTime.now()),"old owner cannot release successor","oldReleased="+stale+", owner="+current.getLockedBy());
            check("owner-release",batch.releaseLock(type,id,successor),"successor releases","owner="+successor);
        } else {
            check("owner-release",batch.releaseLock(type,id,runId),"owner releases","owner="+runId);
            metadata.updateMetadataGuarded(id,"released");
            check("released-write",albums.findById(id).orElseThrow().getTitle().equals("released"),"released",albums.findById(id).orElseThrow().getTitle());
        }
    }
    private void messages(ConfigurableApplicationContext app,String variant,String id)throws Exception {
        var listener=app.getBean(AlbumRegistrationListener.class);var albums=app.getBean(AlbumRepository.class);var messages=app.getBean(ProcessedMessageRepository.class);
        var event=new AlbumRegisteredEvent(id,"registered");boolean retry=variant.equals("RETRY_AFTER_FAILURE"), failure=variant.equals("PROCESSING_FAILURE");
        if(retry || failure){
            boolean thrown=false;try{listener.onMessage(new AlbumRegisteredEvent(id,AlbumRegisteredEvent.FORCE_FAIL_TITLE));}catch(IllegalStateException expected){thrown=true;}
            var record=messages.findById(id).orElseThrow();
            check("failed-attempt",thrown && record.getStatus()==MessageStatus.FAILED && record.getAttemptCount()==1 && albums.count()==0,
                    "exception, FAILED, attempts=1, albums=0","exception="+thrown+", status="+record.getStatus()+", attempts="+record.getAttemptCount()+", albums="+albums.count());
            if(failure){check("processing-completed",false,"successful processing","injected business failure; retry not requested; FAILED row retained");return;}
        }
        if(variant.equals("CONCURRENT")){
            var executor=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
            try {
                Callable<Void> delivery=()->{ready.countDown();if(!go.await(5,TimeUnit.SECONDS))throw new TimeoutException("delivery gate");listener.onMessage(event);return null;};
                var first=executor.submit(delivery);var second=executor.submit(delivery);
                if(!ready.await(5,TimeUnit.SECONDS))throw new TimeoutException("delivery readiness");go.countDown();
                first.get(30,TimeUnit.SECONDS);second.get(30,TimeUnit.SECONDS);
            } finally {go.countDown();executor.shutdownNow();if(!executor.awaitTermination(35,TimeUnit.SECONDS))throw new IllegalStateException("delivery threads did not stop");}
            check("concurrent-delivery",true,"both calls complete; two threads released together","two bounded tasks completed; no swallowed exception");
        } else {listener.onMessage(event);listener.onMessage(event);}
        var done=messages.findById(id).orElseThrow();
        check("single-registration",albums.count()==1 && done.getStatus()==MessageStatus.DONE && done.getAttemptCount()==(retry?2:1),
                "albums=1, DONE, attempts="+(retry?2:1),"albums="+albums.count()+", status="+done.getStatus()+", attempts="+done.getAttemptCount());
        listener.onMessage(new AlbumRegisteredEvent(id,"must-not-register"));
        check("done-not-reprocessed",albums.count()==1 && albums.findByTitle("must-not-register").isEmpty() && messages.findById(id).orElseThrow().getAttemptCount()==(retry?2:1),
                "DONE duplicate skipped, no new album or attempt","albums="+albums.count()+", attempts="+messages.findById(id).orElseThrow().getAttemptCount());
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
        }, "mds-deadline"); deadline.setDaemon(true); deadline.start();
        var identity = new WorkerProtocol.Identity(command.path("runId").asText(), "mds-distribution", experiment,
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
        var worker = new MdsWorker(writer);
        try {
            worker.execute(experiment, parameters, command.path("runId").asText());
        } catch (Exception e) {
            worker.failed = true;
            worker.check("execution",false,"completed","execution failed: " + e.getClass().getSimpleName());
            System.err.println("job_id=" + identity.runId() + " stage=mds reason=EXECUTION_FAILED"); e.printStackTrace(System.err);
        }
        try {
            writer.emit("PHASE", WireJson.object().put("phase","FINALIZING"));
            Path file=workspace.resolve("mds-report.json"); Files.write(file,WireJson.encode(worker.report),StandardOpenOption.CREATE_NEW);
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
