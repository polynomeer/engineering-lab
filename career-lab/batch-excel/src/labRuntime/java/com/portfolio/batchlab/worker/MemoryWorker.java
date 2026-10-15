package com.portfolio.batchlab.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pnomeer.lab.worker.*;
import com.portfolio.batchlab.BatchLabApplication;
import com.portfolio.batchlab.batch.BatchProperties;
import com.portfolio.batchlab.excel.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xssf.eventusermodel.*;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** One bounded synthetic variant per new JVM. Existing domain jobs/exporters are reused. */
public final class MemoryWorker {
    private final WorkerProtocol.Writer writer;
    private final ObjectNode report=WireJson.object().put("provenance","FACT-based mechanisms; NEW-DESIGN bounded synthetic fixtures; not historical performance");
    private final ObjectNode counters=WireJson.object();
    private boolean failed, measuring;
    private MemoryWorker(WorkerProtocol.Writer writer){this.writer=writer;report.putArray("checks");}
    private void measuring()throws Exception {if(!measuring){writer.emit("PHASE",WireJson.object().put("phase","MEASURING"));measuring=true;}}
    public static void validate(String experiment,JsonNode p){
        if(!Set.of("career.batch-excel.batch-memory","career.batch-excel.excel-memory").contains(experiment))throw new IllegalArgumentException("Unsupported experiment");
        var keys=new HashSet<String>();p.fieldNames().forEachRemaining(keys::add);
        if(!keys.equals(Set.of("variant","rows","seed")) || !Set.of("BEFORE","AFTER","FAIL_AFTER_MEASUREMENT").contains(p.path("variant").asText()))throw new IllegalArgumentException("Invalid parameters");
        integer(p,"rows",1,5000);integer(p,"seed",0,Integer.MAX_VALUE);
    }
    private static void integer(JsonNode p,String key,long min,long max){if(!p.path(key).isIntegralNumber() || !p.path(key).canConvertToLong() || p.path(key).asLong()<min || p.path(key).asLong()>max)throw new IllegalArgumentException("Invalid "+key);}
    private void check(String id,boolean pass,String expected,String observed)throws Exception {
        measuring();var row=WireJson.object().put("checkId",id).put("status",pass?"PASS":"FAIL").put("expected",expected).put("observed",observed)
                .put("description","Synthetic correctness check; sampled heap is not the true peak, live set, RSS or historical performance");
        writer.emit("CHECK",row);report.withArray("checks").add(row);failed|=!pass;
    }
    private void execute(String experiment,JsonNode p,String runId)throws Exception {
        String variant=p.path("variant").asText();int rows=p.path("rows").asInt();long seed=p.path("seed").asLong();boolean batch=experiment.endsWith("batch-memory");
        report.put("experimentId",experiment).put("variant",variant).put("seed",seed).put("rows",rows).put("targetMonth","2026-01");
        report.set("parameters",p.deepCopy());
        report.put("heapMethod","MemoryMXBean heap used, periodic plus boundary samples, no explicit GC; may include garbage and miss peaks")
                .put("samplingIntervalMs",SampledHeap.INTERVAL_MS).put("forcedGc",false).put("maxHeapBytes",Runtime.getRuntime().maxMemory())
                .put("jvmInstanceId",UUID.randomUUID().toString()).put("chunkSize",BatchProperties.CHUNK_SIZE)
                .put("excelPageSize",BatchProperties.EXCEL_QUERY_CHUNK_SIZE).put("sxssfWindowSize",BatchProperties.SXSSF_WINDOW_SIZE)
                .put("measurementScope","JobLauncher.run or export only; excludes Spring/schema startup, seed, result validation and cleanup; cold execution, no warmup");
        if(!"batch-db".equals(System.getenv("LAB_BATCH_DB_HOST")) || !System.getenv().getOrDefault("LAB_BATCH_DB_PASSWORD","").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Controller database endpoint required");
        try(var app=new SpringApplicationBuilder(BatchLabApplication.class).web(WebApplicationType.NONE).run(
                "--spring.main.banner-mode=off","--logging.level.root=WARN",
                "--spring.datasource.url=jdbc:mysql://batch-db:3306/batch_lab?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true&useServerPrepStmts=false&connectTimeout=5000&socketTimeout=30000",
                "--spring.datasource.username=lab","--spring.datasource.password="+System.getenv("LAB_BATCH_DB_PASSWORD"),
                "--spring.datasource.hikari.maximum-pool-size=4","--spring.datasource.hikari.minimum-idle=1","--spring.datasource.hikari.connection-timeout=10000")) {
            var jdbc=app.getBean(JdbcTemplate.class);jdbc.setQueryTimeout(30);
            long sum=seed(jdbc,rows,seed,batch?"PENDING":"AGGREGATED");
            report.put("seedCompletedEpochMs",System.currentTimeMillis()).put("seedGrossSum",sum);
            check("seed-count",Objects.equals((long)rows,jdbc.queryForObject("SELECT COUNT(*) FROM settlement_record",Long.class)),Integer.toString(rows),Long.toString(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_record",Long.class)));
            Path output=null;JobExecution execution=null;
            var probe=new SampledHeap();
            try {
                if(batch){String job=variant.equals("BEFORE")?"legacyTaskletAggregationJob":"chunkAggregationJob";
                    execution=app.getBean(JobLauncher.class).run(app.getBean(job,Job.class),new JobParametersBuilder().addString("targetMonth","2026-01").addString("runId",runId).toJobParameters());
                } else output=variant.equals("BEFORE")?app.getBean(LegacyExcelExportService.class).export("2026-01"):app.getBean(StreamingExcelExportService.class).export("2026-01");
            } finally {
                probe.close();var memory=probe.result();
                counters.put("memory.baselineBytes",memory.baselineBytes()).put("memory.sampledPeakBytes",memory.sampledPeakBytes())
                        .put("memory.sampledDeltaBytes",memory.sampledPeakBytes()-memory.baselineBytes()).put("memory.samples",memory.samples())
                        .put("memory.elapsedNanos",memory.elapsedNanos()).put("memory.heapLimitBytes",Runtime.getRuntime().maxMemory());
                report.put("measurementStartEpochMs",memory.startEpochMs()).put("measurementEndEpochMs",memory.endEpochMs());
            }
            var sampled=probe.result();
            check("measurement-window",sampled.startEpochMs()>=report.path("seedCompletedEpochMs").asLong() && sampled.endEpochMs()>=sampled.startEpochMs() && sampled.samples()>=2,
                    "seed before measured work; periodic and boundary samples; no explicit GC",
                    "seed="+seed+", rows="+rows+", intervalMs="+SampledHeap.INTERVAL_MS+", samples="+sampled.samples()+", maxHeapBytes="+Runtime.getRuntime().maxMemory()
                    +", seedEnd="+report.path("seedCompletedEpochMs").asLong()+", start="+sampled.startEpochMs()+", end="+sampled.endEpochMs()+"; sampled heap is not true peak/live set/RSS");
            if(batch){
                check("workload-completed",execution.getStatus()==BatchStatus.COMPLETED,"COMPLETED",execution.getStatus().name());
                long stored=jdbc.queryForObject("SELECT COUNT(*) FROM settlement_record WHERE settlement_status='AGGREGATED'",Long.class);
                counters.put("memory.processedRows",stored);
                check("output-integrity",stored==rows && Objects.equals(sum,jdbc.queryForObject("SELECT SUM(gross_amount) FROM settlement_record",Long.class)),rows+" aggregated rows; gross sum="+sum,"aggregated="+stored);
            } else {
                try {check("workload-completed",Files.isRegularFile(output),"XLSX created","bytes="+Files.size(output));
                    counters.put("memory.outputBytes",Files.size(output));
                    int verified=verifyWorkbook(output,rows,seed);counters.put("memory.processedRows",verified);
                    check("output-integrity",verified==rows,"all six cells of each ordered row match seed",Integer.toString(verified));
                } finally {Files.deleteIfExists(output);}
            }
            if(variant.equals("FAIL_AFTER_MEASUREMENT"))check("injected-finalization-failure",false,"successful finalization","NEW-DESIGN deliberate failure after measurement and validation; no batch restart or rollback claim");
        }
    }
    private static long seed(JdbcTemplate jdbc,int rows,long seed,String status){
        var random=new SplittableRandom(seed);long[] sum={0};
        for(int offset=0;offset<rows;offset+=1000){int start=offset,length=Math.min(1000,rows-offset);
            jdbc.batchUpdate("INSERT INTO settlement_record(subscription_id,target_month,gross_amount,fee_amount,net_amount,settlement_status,created_at) VALUES (?,'2026-01',?,?,?,?, '2026-01-01 00:00:00')",new BatchPreparedStatementSetter(){
                public int getBatchSize(){return length;}
                public void setValues(PreparedStatement ps,int i)throws SQLException {int gross=5000+random.nextInt(45000),fee=(int)Math.round(gross*0.1);sum[0]+=gross;
                    ps.setLong(1,start+i+1);ps.setInt(2,gross);ps.setInt(3,fee);ps.setInt(4,gross-fee);ps.setString(5,status);}
            });
        }return sum[0];
    }
    /** Streaming readback is outside measurement; never loads the whole output workbook. */
    private static int verifyWorkbook(Path output,int expectedRows,long seed)throws Exception {
        var random=new SplittableRandom(seed);int[] count={0};
        try(var pkg=OPCPackage.open(output.toFile(),PackageAccess.READ)){
            var reader=new XSSFReader(pkg);var sheets=reader.getSheetsData();
            if(!sheets.hasNext())throw new IOException("Missing worksheet");
            var handler=new XSSFSheetXMLHandler.SheetContentsHandler(){
                String[] cells;int current;
                public void startRow(int row){if(row!=count[0])throw new IllegalStateException("Noncontiguous row");current=row;cells=new String[6];}
                public void cell(String reference,String value,XSSFComment comment){int column=new CellReference(reference).getCol();if(column<0 || column>=6 || cells[column]!=null)throw new IllegalStateException("Invalid cell");cells[column]=value;}
                public void endRow(int row){
                    String[] expected;
                    if(current==0)expected=new String[]{"ID","구독ID","정산월","총액","수수료","순액"};
                    else {if(current>expectedRows)throw new IllegalStateException("Extra row");int gross=5000+random.nextInt(45000),fee=(int)Math.round(gross*0.1);
                        expected=new String[]{Integer.toString(current),Integer.toString(current),"2026-01",Integer.toString(gross),Integer.toString(fee),Integer.toString(gross-fee)};}
                    if(!Arrays.equals(expected,cells))throw new IllegalStateException("Output mismatch at row "+current);count[0]++;
                }
            };
            try(var sheet=sheets.next()) {var xml=XMLHelper.newXMLReader();xml.setContentHandler(new XSSFSheetXMLHandler(reader.getStylesTable(),reader.getSharedStringsTable(),handler,new DataFormatter(Locale.ROOT),false));xml.parse(new InputSource(sheet));}
            if(sheets.hasNext())throw new IOException("Unexpected extra worksheet");
        }return count[0]-1;
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
        }, "memory-deadline"); deadline.setDaemon(true); deadline.start();
        var identity = new WorkerProtocol.Identity(command.path("runId").asText(), "batch-excel", experiment,
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
        var worker = new MemoryWorker(writer);
        try {
            worker.execute(experiment, parameters, command.path("runId").asText());
        } catch (Exception e) {
            worker.failed = true;
            worker.check("execution",false,"completed","execution failed: " + e.getClass().getSimpleName());
            System.err.println("job_id=" + identity.runId() + " stage=memory reason=EXECUTION_FAILED"); e.printStackTrace(System.err);
        }
        try {
            if (!worker.counters.isEmpty()) {
                var metric=WireJson.object().put("timestampEpochMs",System.currentTimeMillis());
                metric.set("gauges",WireJson.object()); metric.set("labels",WireJson.object().put("variant",variant)); metric.set("counters",worker.counters);
                writer.emit("METRIC",metric);
            }
            writer.emit("PHASE", WireJson.object().put("phase","FINALIZING"));
            worker.report.set("metrics",worker.counters);
            Path file=workspace.resolve("memory-report.json"); Files.write(file,WireJson.encode(worker.report),StandardOpenOption.CREATE_NEW);
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
