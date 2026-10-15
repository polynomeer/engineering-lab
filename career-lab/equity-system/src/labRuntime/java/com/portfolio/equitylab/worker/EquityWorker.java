package com.portfolio.equitylab.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.worker.*;
import com.portfolio.equitylab.deletion.*;
import com.portfolio.equitylab.lock.*;
import com.portfolio.equitylab.registration.*;
import com.portfolio.equitylab.repository.*;
import com.portfolio.equitylab.support.EquityShareTestDataGenerator;
import com.zaxxer.hikari.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** FACT-based domain behavior, NEW-DESIGN bounded fixtures and measurement contract. */
public final class EquityWorker {
    private final WorkerProtocol.Writer writer;
    private final ObjectNode report = WireJson.object().put("provenance", "FACT-based mechanisms; NEW-DESIGN synthetic workload; not historical performance");
    private final ObjectNode counters = WireJson.object();
    private boolean failed, measuring;
    private void measuring() throws Exception { if (!measuring) { writer.emit("PHASE",WireJson.object().put("phase","MEASURING")); measuring=true; } }
    private EquityWorker(WorkerProtocol.Writer writer) { this.writer = writer; report.putArray("checks"); }
    public static void validate(String experiment, JsonNode p) {
        String name = experiment.replace("career.equity-system.", "");
        var variants = name.equals("registration") ? Set.of("BEFORE", "AFTER", "INVALID_ROW") : Set.of("BEFORE", "AFTER");
        var keys = new HashSet<String>(); p.fieldNames().forEachRemaining(keys::add);
        var expected = switch (name) {
            case "deletion" -> Set.of("variant", "rows");
            case "registration" -> Set.of("variant", "rows", "chunkSize", "workers", "maxInFlight");
            case "redis-lock" -> Set.of("variant");
            default -> throw new IllegalArgumentException("Unsupported experiment");
        };
        if (!experiment.equals("career.equity-system." + name) || !keys.equals(expected) || !variants.contains(p.path("variant").asText()))
            throw new IllegalArgumentException("Invalid parameters");
        if (!name.equals("redis-lock")) integer(p,"rows",1,name.equals("deletion") ? 50000 : 10000);
        if (name.equals("registration")) { integer(p,"chunkSize",1,1000); integer(p,"workers",1,8); integer(p,"maxInFlight",1,16); }
    }
    private static void integer(JsonNode p, String key, int min, int max) {
        if (!p.path(key).isIntegralNumber() || !p.path(key).canConvertToInt() || p.path(key).asInt() < min || p.path(key).asInt() > max)
            throw new IllegalArgumentException("Invalid " + key);
    }
    private void check(String id, boolean pass, String expected, String observed) throws Exception {
        measuring();
        var row = WireJson.object().put("checkId", id).put("status", pass ? "PASS" : "FAIL").put("expected", expected)
                .put("observed", observed).put("description", "Expectation for this synthetic variant; PASS is not a claim of production safety");
        writer.emit("CHECK", row); report.withArray("checks").add(row); failed |= !pass;
    }
    private void execute(String experiment, JsonNode p) throws Exception {
        String variant = p.path("variant").asText(); report.put("experimentId",experiment).put("variant",variant); report.set("parameters",p.deepCopy());
        if (experiment.endsWith("redis-lock")) { redis(variant); return; }
        if (!"equity-db".equals(System.getenv("LAB_EQUITY_DB_HOST")) || !System.getenv().getOrDefault("LAB_EQUITY_DB_PASSWORD", "").matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Controller database endpoint required");
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://equity-db:3306/equity_lab?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=30000");
        config.setUsername("lab"); config.setPassword(System.getenv("LAB_EQUITY_DB_PASSWORD"));
        config.setMaximumPoolSize(8); config.setMinimumIdle(1); config.setConnectionTimeout(10000);
        try (var ds = new HikariDataSource(config)) {
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__init_equity_share.sql")).execute(ds);
            var jdbc = new JdbcTemplate(ds); jdbc.setQueryTimeout(30);
            if (experiment.endsWith("deletion")) deletion(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), p);
            else registration(jdbc, p);
        }
    }
    private void deletion(JdbcTemplate jdbc, TransactionTemplate transaction, JsonNode p) throws Exception {
        int rows = p.path("rows").asInt(); boolean before = p.path("variant").asText().equals("BEFORE");
        var start = LocalDate.of(2026,1,1); var end = start.plusDays(30);
        var generator = new EquityShareTestDataGenerator(jdbc);
        generator.seedSinglePeriod("TARGET", rows, start, end);
        generator.seedSinglePeriod("KEEP", 37, start, end);
        var dao = new EquityShareDeletionDao(jdbc);
        if (!before) dao.addPerformanceIndex();
        check("seed-count", dao.countAll() == rows + 37L, Long.toString(rows + 37L), Long.toString(dao.countAll()));
        measuring();
        long began = System.nanoTime(); int deleted, chunks; long maxMs;
        if (before) {
            deleted = Objects.requireNonNull(transaction.execute(status -> new LegacyDeletionService(dao).deleteAll("TARGET",start,end)));
            maxMs = (System.nanoTime() - began) / 1000000; chunks = 1;
        } else {
            var result = new OptimizedDeletionService(dao).deleteAll("TARGET",start,end);
            deleted = result.deletedRows(); chunks = result.chunkCount(); maxMs = result.maxSingleStepDurationMs();
        }
        counters.put("deletion.elapsedNanos",System.nanoTime()-began).put("deletion.maxSingleStepMs",maxMs)
                .put("deletion.deletedRows",deleted).put("deletion.chunks",chunks);
        check("deleted-count",deleted==rows,Integer.toString(rows),Integer.toString(deleted));
        check("target-empty",dao.countByDistributor("TARGET")==0,"0",Long.toString(dao.countByDistributor("TARGET")));
        check("unrelated-preserved",dao.countByDistributor("KEEP")==37,"37",Long.toString(dao.countByDistributor("KEEP")));
        report.put("timingScope","Measured delete call only; maxSingleStepMs is a transaction-duration proxy including JDBC/commit, NOT measured lock occupancy. Setup/index/seed excluded. No speed ordering assertion.");
    }
    private void registration(JdbcTemplate jdbc, JsonNode p) throws Exception {
        int count=p.path("rows").asInt(); String variant=p.path("variant").asText();
        // Lazy input: no row-count-sized materialized fixture or futures list.
        List<RawEquityRow> rows = new AbstractList<>() {
            public int size() { return count; }
            public RawEquityRow get(int i) { Objects.checkIndex(i,count); return new RawEquityRow("REGISTER",i+1,LocalDate.of(2026,1,1),LocalDate.of(2026,1,31),
                    variant.equals("INVALID_ROW") && i==count-1 ? "101" : "0.50"); }
        };
        check("input-count",true,Integer.toString(count),Integer.toString(rows.size()));
        measuring();
        long began=System.nanoTime(); int returned=-1; Exception failure=null;
        try {
            var processor=new RowProcessor(); var dao=new EquityShareInsertDao(jdbc);
            if (variant.equals("BEFORE")) returned=new SequentialRegistrationPipeline(processor,dao).register(rows);
            else {
                var result=new ParallelRegistrationPipeline(processor,dao).registerMeasured(rows,p.path("chunkSize").asInt(),p.path("workers").asInt(),p.path("maxInFlight").asInt());
                returned=result.insertedRows(); counters.put("registration.maxInFlightChunks",result.maxInFlightChunks())
                        .put("registration.backpressureWaits",result.backpressureWaits()).put("registration.backpressureNanos",result.backpressureNanos());
            }
        } catch (Exception e) { failure=e; }
        counters.put("registration.elapsedNanos",System.nanoTime()-began);
        long stored=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM equity_share",Long.class));
        counters.put("registration.storedRows",stored);
        check("registration-completed",failure==null,"all rows validated and inserted",failure==null ? "completed" : variant.equals("INVALID_ROW") && failure.getCause() instanceof IllegalArgumentException ? "rejected row index="+(count-1)+", track_id="+count+", share=101; earlier chunks may be committed" : "execution failed: "+failure.getClass().getSimpleName());
        check("stored-count",failure==null && returned==count && stored==count,Integer.toString(count),"returned="+returned+", stored="+stored);
        report.put("timingScope","Includes existing synthetic 2ms row wait; excludes schema and startup. BEFORE uses fixed batch 1000; AFTER uses submitted bounds. Partial failure metrics omit unavailable admission totals. No historic throughput claim.");
        if (failure!=null) { System.err.println("stage=registration reason=ROW_PROCESSING_FAILED"); failure.printStackTrace(System.err); }
    }
    private void redis(String variant) throws Exception {
        if (!"equity-db".equals(System.getenv("LAB_EQUITY_REDIS_HOST"))) throw new IllegalArgumentException("Controller Redis endpoint required");
        var factory=new LettuceConnectionFactory("equity-db",6379); factory.afterPropertiesSet(); factory.start();
        try {
            var template=new StringRedisTemplate(factory); var legacy=new LegacyRedisLock(template); var safe=new SafeRedisLock(template);
            boolean before=variant.equals("BEFORE"); String key="equity-lock";
            measuring();
            boolean acquired=before ? legacy.tryAcquire(key,"A") : safe.tryAcquire(key,"A",Duration.ofSeconds(30));
            check("first-owner",acquired,"acquired","acquired="+acquired);
            long ttl=template.getExpire(key,TimeUnit.MILLISECONDS);
            check("ttl-policy",before ? ttl==-1 : ttl>0 && ttl<=30000,before ? "no TTL (-1)" : "0 < TTL <= 30000ms",Long.toString(ttl));
            // Explicit synthetic ownership transition, not a claim that the legacy lock expires itself.
            template.delete(key);
            boolean b=before ? legacy.tryAcquire(key,"B") : safe.tryAcquire(key,"B",Duration.ofSeconds(30));
            check("replacement-owner",b,"B acquired","acquired="+b);
            if (before) legacy.release(key,"A"); else safe.release(key,"A");
            String token=template.opsForValue().get(key);
            check("delayed-release",before ? token==null : "B".equals(token),before ? "B incorrectly deleted" : "B preserved",String.valueOf(token));
            if (!before) {
                boolean expiryAcquired=safe.tryAcquire("expiry","T",Duration.ofMillis(150));
                long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
                while (Boolean.TRUE.equals(template.hasKey("expiry")) && System.nanoTime()<deadline) Thread.sleep(20);
                check("ttl-expiration",expiryAcquired && Boolean.FALSE.equals(template.hasKey("expiry")),"acquired then expired","acquired="+expiryAcquired+", exists="+template.hasKey("expiry"));
                check("owner-release",safe.release(key,"B"),"B can release","remaining="+template.opsForValue().get(key));
            }
            report.put("timingScope","CHECK-only; manual ownership replacement is a NEW-DESIGN deterministic interleaving; TTL expiration is observed on real Redis.");
        } finally { factory.destroy(); }
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
        // No external resources: process termination is sufficient on parent loss/deadline.
        ParentConnection.watch(System.in, () -> Runtime.getRuntime().halt(20));
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(remaining); Runtime.getRuntime().halt(21); } catch (InterruptedException ignored) {}
        }, "equity-deadline"); deadline.setDaemon(true); deadline.start();
        var identity = new WorkerProtocol.Identity(command.path("runId").asText(), "equity-system", experiment,
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
        var worker = new EquityWorker(writer);
        try {
            worker.execute(experiment, parameters);
        } catch (Exception e) {
            worker.failed = true;
            worker.check("execution",false,"completed","execution failed: " + e.getClass().getSimpleName());
            System.err.println("job_id=" + identity.runId() + " stage=equity reason=EXECUTION_FAILED"); e.printStackTrace(System.err);
        }
        try {
            if (!worker.counters.isEmpty()) {
                var metric=WireJson.object().put("timestampEpochMs",System.currentTimeMillis());
                metric.set("gauges",WireJson.object()); metric.set("labels",WireJson.object().put("variant",variant)); metric.set("counters",worker.counters);
                writer.emit("METRIC",metric);
            }
            writer.emit("PHASE", WireJson.object().put("phase","FINALIZING"));
            worker.report.set("metrics",worker.counters);
            Path file=workspace.resolve("equity-report.json"); Files.write(file,WireJson.encode(worker.report),StandardOpenOption.CREATE_NEW);
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
