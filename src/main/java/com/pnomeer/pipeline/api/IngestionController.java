package com.pnomeer.pipeline.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/ingest")
public class IngestionController {
    private final IngestionService ingestionService;
    private final JdbcTemplate jdbcTemplate;

    public IngestionController(IngestionService ingestionService, JdbcTemplate jdbcTemplate) {
        this.ingestionService = ingestionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartIngestionResponse startExcelIngestion(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "PIPELINE") com.pnomeer.pipeline.PipelineRunner.RunMode mode) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must not be empty");
        }
        return new StartIngestionResponse(ingestionService.startJob(file, mode), mode);
    }

    @GetMapping("/jobs/{jobId}")
    public IngestionJobResponse getJob(@PathVariable String jobId) {
        IngestionJobState state = ingestionService.getJob(jobId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found");
        }
        return new IngestionJobResponse(
                state.getJobId(),
                state.getStatus(),
                state.getFileName(),
                state.getRunMode(),
                state.getCreatedAtEpochMs(),
                state.getUpdatedAtEpochMs(),
                state.getStartedAtEpochMs(),
                state.getCompletedAtEpochMs(),
                state.getFirstProgressAtEpochMs(),
                state.getProducedCount(),
                state.getInsertedCount(),
                state.getValidationErrorCount(),
                state.getErrorSummary(),
                state.getFailureMessage(),
                state.getLatestProgress());
    }

    @GetMapping("/jobs")
    public IngestionJobsResponse listJobs() {
        var jobs = ingestionService.listJobs().stream()
                .map(state -> new IngestionJobResponse(
                        state.getJobId(),
                        state.getStatus(),
                        state.getFileName(),
                        state.getRunMode(),
                        state.getCreatedAtEpochMs(),
                        state.getUpdatedAtEpochMs(),
                        state.getStartedAtEpochMs(),
                        state.getCompletedAtEpochMs(),
                        state.getFirstProgressAtEpochMs(),
                        state.getProducedCount(),
                        state.getInsertedCount(),
                        state.getValidationErrorCount(),
                        state.getErrorSummary(),
                        state.getFailureMessage(),
                        state.getLatestProgress()))
                .toList();
        return new IngestionJobsResponse(jobs);
    }

    @GetMapping("/jobs/{jobId}/timeline")
    public IngestionTimelineResponse getJobTimeline(@PathVariable String jobId) {
        IngestionJobState state = ingestionService.getJob(jobId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found");
        }
        return new IngestionTimelineResponse(state.getJobId(), state.getStatus(), state.getProgressTimeline());
    }

    @GetMapping("/db/count")
    public DbCountResponse getDbCount() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from ingest_item", Integer.class);
        return new DbCountResponse(count == null ? 0 : count);
    }

    @GetMapping("/db/rows")
    public DbRowsResponse getDbRows(@RequestParam(defaultValue = "200") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 2000));
        var rows = jdbcTemplate.query(
                "select id, col1, col2, col3 from ingest_item order by id desc limit ?",
                (rs, rowNum) -> new DbRow(
                        rs.getLong("id"),
                        rs.getString("col1"),
                        rs.getString("col2"),
                        rs.getInt("col3")),
                safeLimit);
        return new DbRowsResponse(safeLimit, rows);
    }

    @GetMapping(value = "/ui/{jobId}", produces = MediaType.TEXT_HTML_VALUE)
    public String getJobVisualization(@PathVariable String jobId) {
        String safeJobId = HtmlUtils.htmlEscape(jobId);
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>Ingestion Job %s</title>
                  <style>
                    body { font-family: ui-sans-serif, system-ui, -apple-system, sans-serif; margin: 0; background: #282a36; color: #f8f8f2; }
                    .wrap { max-width: 1080px; margin: 24px auto; padding: 0 16px; }
                    .card { background: #1f2330; border: 1px solid #44475a; border-radius: 10px; padding: 14px; margin-bottom: 14px; }
                    .grid { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 10px; }
                    .k { font-size: 12px; color: #bd93f9; }
                    .v { font-size: 22px; font-weight: 700; }
                    svg { width: 100%%; height: 180px; background: #1a1d29; border-radius: 8px; border: 1px solid #44475a; }
                    .small { font-size: 12px; color: #6272a4; }
                    @media (max-width: 900px) { .grid { grid-template-columns: repeat(2, minmax(0,1fr)); } }
                  </style>
                </head>
                <body>
                <div class="wrap">
                  <div class="card">
                    <div class="small">Job ID: <code id="jobId">%s</code></div>
                    <div class="small">Status: <span id="status">RUNNING</span></div>
                    <div class="small">Mode: <span id="mode">PIPELINE</span></div>
                    <div class="small">Processing Time: <span id="elapsed">0.0s</span></div>
                  </div>

                  <div class="card grid">
                    <div><div class="k">Total Processing Time</div><div class="v" id="totalProc">0.0s</div></div>
                    <div><div class="k">Job Completion Time</div><div class="v small" id="completionTime">-</div></div>
                    <div><div class="k">Time To Publish</div><div class="v" id="timeToPublish">0ms</div></div>
                    <div><div class="k">Started At</div><div class="v small" id="startedAt">-</div></div>
                  </div>

                  <div class="card grid">
                    <div><div class="k">Produced</div><div class="v" id="produced">0</div></div>
                    <div><div class="k">Mapped</div><div class="v" id="mapped">0</div></div>
                    <div><div class="k">Inserted</div><div class="v" id="inserted">0</div></div>
                    <div><div class="k">Validation Errors</div><div class="v" id="errors">0</div></div>
                  </div>

                  <div class="card">
                    <div class="k">Queue Sizes</div>
                    <svg id="queueChart" viewBox="0 0 1000 180" preserveAspectRatio="none"></svg>
                    <div class="small">raw depth: <span id="rawQ">0</span>, mapped depth: <span id="mappedQ">0</span>, raw sat: <span id="rawSat">0</span>%%, mapped sat: <span id="mappedSat">0</span>%%</div>
                  </div>

                  <div class="card">
                    <div class="k">Queue Wait/Latency (ms)</div>
                    <svg id="queueMetricChart" viewBox="0 0 1000 180" preserveAspectRatio="none"></svg>
                    <div class="small">
                      raw enqueue wait: <span id="rawEnqMs">0.00</span>,
                      mapped enqueue wait: <span id="mappedEnqMs">0.00</span>,
                      raw dequeue latency: <span id="rawDeqMs">0.00</span>,
                      mapped dequeue latency: <span id="mappedDeqMs">0.00</span>
                    </div>
                  </div>

                  <div class="card">
                    <div class="k">Throughput</div>
                    <svg id="rateChart" viewBox="0 0 1000 180" preserveAspectRatio="none"></svg>
                    <div class="small">rows/sec: <span id="rowRate">0</span>, records/sec: <span id="recordRate">0</span>, batch/sec: <span id="batchRate">0</span></div>
                  </div>

                  <div class="card">
                    <div class="k">Stage Latency (ms)</div>
                    <svg id="latencyChart" viewBox="0 0 1000 180" preserveAspectRatio="none"></svg>
                    <div class="small">parse: <span id="parseLat">0.00</span>, validation: <span id="validationLat">0.00</span>, mapping: <span id="mappingLat">0.00</span>, insert: <span id="insertLat">0.00</span></div>
                  </div>

                  <div class="card">
                    <div class="k">Backpressure Indicators</div>
                    <svg id="backpressureChart" viewBox="0 0 1000 180" preserveAspectRatio="none"></svg>
                    <div class="small">
                      enqueue blocking: <span id="enqBlockMs">0.00</span>ms,
                      producer slowdown: <span id="producerSlowdown">0.0</span>%%,
                      stall frequency: <span id="stallFreq">0.00</span>/min,
                      stall events: <span id="stallEvents">0</span>
                    </div>
                  </div>

                  <div class="card">
                    <div class="k">Resource Utilization</div>
                    <svg id="resourceChart" viewBox="0 0 1000 180" preserveAspectRatio="none"></svg>
                    <div class="small">
                      heap usage: <span id="heapUsedMb">0.0</span>/<span id="heapMaxMb">0.0</span>MB,
                      peak memory: <span id="peakHeapMb">0.0</span>MB,
                      GC pause: <span id="gcPauseMs">0.0</span>ms
                    </div>
                    <div class="small">
                      DB active connections: <span id="dbActive">0</span>,
                      DB awaiting connections: <span id="dbAwaiting">0</span>,
                      connection wait: <span id="dbConnWaitMs">0.00</span>ms,
                      lock wait time: <span id="dbLockWaitMs">0.00</span>ms
                    </div>
                  </div>
                </div>

                <script>
                  const jobId = "%s";
                  const maxPoints = 120;
                  let timeline = [];

                  function toPath(data, yMax, color) {
                    if (!data.length || yMax <= 0) return `<path d="" stroke="${color}" fill="none" stroke-width="2"/>`;
                    const step = 1000 / Math.max(1, data.length - 1);
                    const pts = data.map((v, i) => {
                      const x = i * step;
                      const y = 170 - (Math.max(0, v) / yMax) * 160;
                      return `${x.toFixed(2)},${y.toFixed(2)}`;
                    }).join(" ");
                    return `<polyline points="${pts}" stroke="${color}" fill="none" stroke-width="2"/>`;
                  }

                  function fmtElapsed(ms) {
                    const value = Math.max(0, Number(ms || 0));
                    if (value < 1000) return `${value}ms`;
                    return `${(value / 1000).toFixed(1)}s`;
                  }

                  function fmtClock(ts) {
                    if (!ts) return "-";
                    return new Date(ts).toLocaleTimeString();
                  }

                  function renderCharts() {
                    const qRaw = timeline.map(s => s.rawQueueSize);
                    const qMapped = timeline.map(s => s.mappedQueueSize);
                    const rP = timeline.map(s => s.producedRatePerSec);
                    const rI = timeline.map(s => s.insertedRatePerSec);
                    const rB = timeline.map(s => s.batchRatePerSec);
                    const lParse = timeline.map(s => s.parseLatencyMs || 0);
                    const lVal = timeline.map(s => s.validationLatencyMs || 0);
                    const lMap = timeline.map(s => s.mappingLatencyMs || 0);
                    const lIns = timeline.map(s => s.insertLatencyMs || 0);
                    const qEnqRaw = timeline.map(s => s.rawEnqueueWaitMs || 0);
                    const qEnqMapped = timeline.map(s => s.mappedEnqueueWaitMs || 0);
                    const qDeqRaw = timeline.map(s => s.rawDequeueLatencyMs || 0);
                    const qDeqMapped = timeline.map(s => s.mappedDequeueLatencyMs || 0);
                    const bpBlock = timeline.map(s => s.enqueueBlockingTimeMs || 0);
                    const bpSlow = timeline.map(s => s.producerSlowdownPct || 0);
                    const bpStall = timeline.map(s => s.pipelineStallFrequencyPerMin || 0);
                    const memHeap = timeline.map(s => s.heapUsedMb || 0);
                    const memPeak = timeline.map(s => s.peakHeapMb || 0);
                    const gcPause = timeline.map(s => s.gcPauseMs || 0);
                    const dbActive = timeline.map(s => s.activeConnections || 0);
                    const dbAwaiting = timeline.map(s => s.awaitingConnections || 0);
                    const dbConnWait = timeline.map(s => s.connectionWaitMs || 0);
                    const dbLockWait = timeline.map(s => s.lockWaitMs || 0);

                    const qMax = Math.max(1, ...qRaw, ...qMapped);
                    const rMax = Math.max(1, ...rP, ...rI, ...rB);
                    const lMax = Math.max(1, ...lParse, ...lVal, ...lMap, ...lIns);
                    const qMetricMax = Math.max(1, ...qEnqRaw, ...qEnqMapped, ...qDeqRaw, ...qDeqMapped);
                    const bpMax = Math.max(1, ...bpBlock, ...bpSlow, ...bpStall);
                    const resourceMax = Math.max(1, ...memHeap, ...memPeak, ...gcPause, ...dbActive, ...dbAwaiting, ...dbConnWait, ...dbLockWait);

                    document.getElementById("queueChart").innerHTML =
                      toPath(qRaw, qMax, "#8be9fd") + toPath(qMapped, qMax, "#ffb86c");
                    document.getElementById("rateChart").innerHTML =
                      toPath(rP, rMax, "#8be9fd") + toPath(rI, rMax, "#50fa7b") + toPath(rB, rMax, "#ff79c6");
                    document.getElementById("latencyChart").innerHTML =
                      toPath(lParse, lMax, "#8be9fd") + toPath(lVal, lMax, "#ffb86c") + toPath(lMap, lMax, "#bd93f9") + toPath(lIns, lMax, "#50fa7b");
                    document.getElementById("queueMetricChart").innerHTML =
                      toPath(qEnqRaw, qMetricMax, "#8be9fd") + toPath(qEnqMapped, qMetricMax, "#ffb86c") + toPath(qDeqRaw, qMetricMax, "#ff79c6") + toPath(qDeqMapped, qMetricMax, "#50fa7b");
                    document.getElementById("backpressureChart").innerHTML =
                      toPath(bpBlock, bpMax, "#ff79c6") + toPath(bpSlow, bpMax, "#8be9fd") + toPath(bpStall, bpMax, "#ffb86c");
                    document.getElementById("resourceChart").innerHTML =
                      toPath(memHeap, resourceMax, "#8be9fd")
                      + toPath(memPeak, resourceMax, "#bd93f9")
                      + toPath(gcPause, resourceMax, "#ff79c6")
                      + toPath(dbActive, resourceMax, "#50fa7b")
                      + toPath(dbAwaiting, resourceMax, "#ffb86c")
                      + toPath(dbConnWait, resourceMax, "#f1fa8c")
                      + toPath(dbLockWait, resourceMax, "#ff5555");
                  }

                  async function poll() {
                    const [jobRes, timelineRes] = await Promise.all([
                      fetch(`/ingest/jobs/${jobId}`),
                      fetch(`/ingest/jobs/${jobId}/timeline`)
                    ]);
                    if (!jobRes.ok) throw new Error("job not found");
                    const job = await jobRes.json();
                    const t = timelineRes.ok ? await timelineRes.json() : { snapshots: [] };
                    timeline = (t.snapshots || []).slice(-maxPoints);

                    document.getElementById("status").textContent = job.status;
                    document.getElementById("mode").textContent = job.runMode || "PIPELINE";
                    document.getElementById("produced").textContent = job.producedCount;
                    document.getElementById("inserted").textContent = job.insertedCount;
                    document.getElementById("errors").textContent = job.validationErrorCount;

                    const latest = job.latestProgress || {};
                    const elapsedMs = latest.elapsedMillis ?? Math.max(0, (job.updatedAtEpochMs || 0) - (job.createdAtEpochMs || 0));
                    document.getElementById("mapped").textContent = latest.mappedCount || 0;
                    document.getElementById("rawQ").textContent = latest.rawQueueSize || 0;
                    document.getElementById("mappedQ").textContent = latest.mappedQueueSize || 0;
                    document.getElementById("rawSat").textContent = (latest.rawQueueSaturationPct || 0).toFixed(1);
                    document.getElementById("mappedSat").textContent = (latest.mappedQueueSaturationPct || 0).toFixed(1);
                    document.getElementById("rawEnqMs").textContent = (latest.rawEnqueueWaitMs || 0).toFixed(2);
                    document.getElementById("mappedEnqMs").textContent = (latest.mappedEnqueueWaitMs || 0).toFixed(2);
                    document.getElementById("rawDeqMs").textContent = (latest.rawDequeueLatencyMs || 0).toFixed(2);
                    document.getElementById("mappedDeqMs").textContent = (latest.mappedDequeueLatencyMs || 0).toFixed(2);
                    document.getElementById("rowRate").textContent = latest.producedRatePerSec || 0;
                    document.getElementById("recordRate").textContent = latest.insertedRatePerSec || 0;
                    document.getElementById("batchRate").textContent = latest.batchRatePerSec || 0;
                    document.getElementById("parseLat").textContent = (latest.parseLatencyMs || 0).toFixed(2);
                    document.getElementById("validationLat").textContent = (latest.validationLatencyMs || 0).toFixed(2);
                    document.getElementById("mappingLat").textContent = (latest.mappingLatencyMs || 0).toFixed(2);
                    document.getElementById("insertLat").textContent = (latest.insertLatencyMs || 0).toFixed(2);
                    document.getElementById("enqBlockMs").textContent = (latest.enqueueBlockingTimeMs || 0).toFixed(2);
                    document.getElementById("producerSlowdown").textContent = (latest.producerSlowdownPct || 0).toFixed(1);
                    document.getElementById("stallFreq").textContent = (latest.pipelineStallFrequencyPerMin || 0).toFixed(2);
                    document.getElementById("stallEvents").textContent = latest.pipelineStallEventCount || 0;
                    document.getElementById("heapUsedMb").textContent = (latest.heapUsedMb || 0).toFixed(1);
                    document.getElementById("heapMaxMb").textContent = (latest.heapMaxMb || 0).toFixed(1);
                    document.getElementById("peakHeapMb").textContent = (latest.peakHeapMb || 0).toFixed(1);
                    document.getElementById("gcPauseMs").textContent = (latest.gcPauseMs || 0).toFixed(1);
                    document.getElementById("dbActive").textContent = latest.activeConnections || 0;
                    document.getElementById("dbAwaiting").textContent = latest.awaitingConnections || 0;
                    document.getElementById("dbConnWaitMs").textContent = (latest.connectionWaitMs || 0).toFixed(2);
                    document.getElementById("dbLockWaitMs").textContent = (latest.lockWaitMs || 0).toFixed(2);
                    document.getElementById("elapsed").textContent = fmtElapsed(elapsedMs);
                    const totalProcessing = Math.max(0, (job.completedAtEpochMs || Date.now()) - (job.startedAtEpochMs || job.createdAtEpochMs || Date.now()));
                    const timeToPublish = Math.max(0, (job.firstProgressAtEpochMs || 0) - (job.createdAtEpochMs || 0));
                    document.getElementById("totalProc").textContent = fmtElapsed(totalProcessing);
                    document.getElementById("completionTime").textContent = fmtClock(job.completedAtEpochMs);
                    document.getElementById("timeToPublish").textContent = fmtElapsed(timeToPublish);
                    document.getElementById("startedAt").textContent = fmtClock(job.startedAtEpochMs);

                    renderCharts();
                  }

                  setInterval(() => poll().catch(console.error), 800);
                  poll().catch(console.error);
                </script>
                </body>
                </html>
                """.formatted(safeJobId, safeJobId, safeJobId);
    }

    @GetMapping(value = "/ui", produces = MediaType.TEXT_HTML_VALUE)
    public String getJobsVisualization() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>Ingestion Jobs Live</title>
                  <style>
                    body { font-family: ui-sans-serif, system-ui, -apple-system, sans-serif; margin: 0; background: #282a36; color: #f8f8f2; }
                    .wrap { max-width: 1200px; margin: 24px auto; padding: 0 16px; }
                    .card { background: #1f2330; border: 1px solid #44475a; border-radius: 10px; padding: 14px; margin-bottom: 14px; }
                    .title { font-size: 28px; font-weight: 700; margin: 0; }
                    .sub { font-size: 12px; color: #6272a4; margin-top: 4px; }
                    .statbar { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 10px; }
                    .k { font-size: 12px; color: #bd93f9; text-transform: uppercase; letter-spacing: 0.5px; }
                    .v { font-size: 24px; font-weight: 700; margin-top: 4px; }
                    .jobs { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 12px; }
                    .job { background: #1f2330; border: 1px solid #44475a; border-radius: 10px; padding: 12px; transition: transform .15s ease, border-color .15s ease; }
                    .job:hover { transform: translateY(-1px); border-color: #8be9fd; }
                    .job.running { animation: glow 1.8s ease-in-out infinite; }
                    .top { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
                    .name { font-size: 14px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                    .badge { font-size: 11px; border-radius: 999px; padding: 3px 8px; border: 1px solid; font-weight: 700; }
                    .RUNNING { color: #ffb86c; border-color: #ffb86c; background: rgba(255,184,108,0.12); }
                    .SUCCEEDED { color: #50fa7b; border-color: #50fa7b; background: rgba(80,250,123,0.12); }
                    .FAILED { color: #ff5555; border-color: #ff5555; background: rgba(255,85,85,0.12); }
                    .row { display: flex; justify-content: space-between; font-size: 12px; margin-top: 7px; color: #f8f8f2; gap: 8px; }
                    .row > span:last-child { text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 65%; }
                    .bar { margin-top: 8px; background: #1a1d29; border: 1px solid #44475a; border-radius: 8px; height: 10px; overflow: hidden; }
                    .fill { height: 100%%; background: linear-gradient(90deg, #bd93f9, #8be9fd); width: 0%%; transition: width .3s ease; }
                    .link { margin-top: 8px; font-size: 12px; }
                    .link a { color: #8be9fd; text-decoration: none; }
                    @keyframes glow { 0%%,100%% { box-shadow: 0 0 0 rgba(139,233,253,0); } 50%% { box-shadow: 0 0 16px rgba(139,233,253,0.25); } }
                    @media (max-width: 900px) { .jobs, .statbar { grid-template-columns: 1fr; } .row > span:last-child { max-width: 55%; } }
                  </style>
                </head>
                <body>
                <div class="wrap">
                  <div class="card">
                    <h1 class="title">Ingestion Jobs</h1>
                    <div class="sub">Live dashboard auto-refreshes every second</div>
                  </div>
                  <div class="card statbar">
                    <div><div class="k">Running</div><div class="v" id="running">0</div></div>
                    <div><div class="k">Succeeded</div><div class="v" id="succeeded">0</div></div>
                    <div><div class="k">Failed</div><div class="v" id="failed">0</div></div>
                    <div><div class="k">Total Inserted</div><div class="v" id="insertedTotal">0</div></div>
                  </div>
                  <div id="jobs" class="jobs"></div>
                </div>
                <script>
                  function fmtTime(ts) { return new Date(ts).toLocaleTimeString(); }
                  function ratio(p, i, e) {
                    const denom = Math.max(1, p - e);
                    return Math.max(0, Math.min(100, Math.round((i / denom) * 100)));
                  }
                  function renderJob(j) {
                    const latest = j.latestProgress || {};
                    const stateClass = j.status === "RUNNING" ? "running" : "";
                    const failure = j.failureMessage ? `<div class="row"><span>failure</span><span>${j.failureMessage}</span></div>` : "";
                    return `
                      <div class="job ${stateClass}">
                        <div class="top">
                          <div class="name">${j.fileName || "unknown.xlsx"}</div>
                          <span class="badge ${j.status}">${j.status}</span>
                        </div>
                        <div class="row"><span>jobId</span><span>${j.jobId}</span></div>
                        <div class="row"><span>mode</span><span>${j.runMode || "PIPELINE"}</span></div>
                        <div class="row"><span>produced / inserted / errors</span><span>${j.producedCount} / ${j.insertedCount} / ${j.validationErrorCount}</span></div>
                        <div class="row"><span>total proc time</span><span>${Math.max(0, (j.completedAtEpochMs || Date.now()) - (j.startedAtEpochMs || j.createdAtEpochMs || Date.now()))}ms</span></div>
                        <div class="row"><span>queues raw|mapped</span><span>${latest.rawQueueSize || 0} | ${latest.mappedQueueSize || 0}</span></div>
                        <div class="row"><span>rate rows|records|batch</span><span>${latest.producedRatePerSec || 0} | ${latest.insertedRatePerSec || 0} | ${latest.batchRatePerSec || 0}</span></div>
                        <div class="row"><span>updated</span><span>${fmtTime(j.updatedAtEpochMs)}</span></div>
                        ${failure}
                        <div class="bar"><div class="fill" style="width:${ratio(j.producedCount, j.insertedCount, j.validationErrorCount)}%"></div></div>
                        <div class="link"><a href="/ingest/ui/${j.jobId}" target="_blank">open detail view</a></div>
                      </div>
                    `;
                  }
                  async function refresh() {
                    const res = await fetch("/ingest/jobs");
                    if (!res.ok) return;
                    const data = await res.json();
                    const jobs = data.jobs || [];
                    const running = jobs.filter(j => j.status === "RUNNING").length;
                    const succeeded = jobs.filter(j => j.status === "SUCCEEDED").length;
                    const failed = jobs.filter(j => j.status === "FAILED").length;
                    const insertedTotal = jobs.reduce((s, j) => s + (j.insertedCount || 0), 0);
                    document.getElementById("running").textContent = running;
                    document.getElementById("succeeded").textContent = succeeded;
                    document.getElementById("failed").textContent = failed;
                    document.getElementById("insertedTotal").textContent = insertedTotal;
                    document.getElementById("jobs").innerHTML = jobs.map(renderJob).join("");
                  }
                  setInterval(() => refresh().catch(console.error), 1000);
                  refresh().catch(console.error);
                </script>
                </body>
                </html>
                """;
    }

    @GetMapping(value = "/ui/db", produces = MediaType.TEXT_HTML_VALUE)
    public String getDbVisualization() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>Ingestion DB View</title>
                  <style>
                    body { font-family: ui-sans-serif, system-ui, -apple-system, sans-serif; margin: 0; background: #282a36; color: #f8f8f2; }
                    .wrap { max-width: 1200px; margin: 24px auto; padding: 0 16px; }
                    .card { background: #1f2330; border: 1px solid #44475a; border-radius: 10px; padding: 14px; margin-bottom: 14px; }
                    .title { font-size: 28px; font-weight: 700; margin: 0; }
                    .sub { font-size: 12px; color: #6272a4; margin-top: 4px; }
                    .controls { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
                    input { background: #1a1d29; color: #f8f8f2; border: 1px solid #44475a; border-radius: 8px; padding: 6px 10px; width: 100px; }
                    button { background: #bd93f9; color: #282a36; border: 0; border-radius: 8px; padding: 6px 10px; font-weight: 700; cursor: pointer; }
                    .stats { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 10px; }
                    .k { font-size: 12px; color: #bd93f9; text-transform: uppercase; letter-spacing: 0.5px; }
                    .v { font-size: 24px; font-weight: 700; margin-top: 4px; }
                    .table-wrap { overflow: auto; max-height: 65vh; border-radius: 8px; border: 1px solid #44475a; }
                    table { width: 100%; border-collapse: collapse; font-size: 13px; }
                    thead th { position: sticky; top: 0; background: #1a1d29; color: #8be9fd; text-align: left; padding: 10px; border-bottom: 1px solid #44475a; }
                    tbody td { padding: 9px 10px; border-bottom: 1px solid #383b4c; }
                    tbody tr:hover { background: #2b3040; }
                    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
                    @media (max-width: 900px) { .stats { grid-template-columns: 1fr; } }
                  </style>
                </head>
                <body>
                <div class="wrap">
                  <div class="card">
                    <h1 class="title">Database View</h1>
                    <div class="sub">Live rows from <code>ingest_item</code> (newest first)</div>
                  </div>

                  <div class="card controls">
                    <label for="limit">Row limit</label>
                    <input id="limit" type="number" min="1" max="2000" value="200" />
                    <button id="applyBtn">Apply</button>
                    <span class="sub">Auto refresh: 2s</span>
                  </div>

                  <div class="card stats">
                    <div><div class="k">Total Rows</div><div class="v" id="totalRows">0</div></div>
                    <div><div class="k">Visible Rows</div><div class="v" id="visibleRows">0</div></div>
                    <div><div class="k">Last Refresh</div><div class="v mono" id="refreshedAt">-</div></div>
                  </div>

                  <div class="card">
                    <div class="table-wrap">
                      <table>
                        <thead>
                          <tr><th>ID</th><th>col1</th><th>col2</th><th>col3</th></tr>
                        </thead>
                        <tbody id="rows"></tbody>
                      </table>
                    </div>
                  </div>
                </div>
                <script>
                  let limit = 200;

                  function esc(s) {
                    return String(s ?? "").replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;");
                  }

                  function renderRows(items) {
                    const body = document.getElementById("rows");
                    body.innerHTML = items.map(r => `
                      <tr>
                        <td class="mono">${r.id}</td>
                        <td>${esc(r.col1)}</td>
                        <td>${esc(r.col2)}</td>
                        <td class="mono">${r.col3}</td>
                      </tr>
                    `).join("");
                    document.getElementById("visibleRows").textContent = items.length;
                  }

                  async function refresh() {
                    const [countRes, rowsRes] = await Promise.all([
                      fetch("/ingest/db/count"),
                      fetch(`/ingest/db/rows?limit=${limit}`)
                    ]);
                    if (!countRes.ok || !rowsRes.ok) return;
                    const count = await countRes.json();
                    const rows = await rowsRes.json();
                    document.getElementById("totalRows").textContent = count.totalRows || 0;
                    renderRows(rows.rows || []);
                    document.getElementById("refreshedAt").textContent = new Date().toLocaleTimeString();
                  }

                  document.getElementById("applyBtn").addEventListener("click", () => {
                    const value = Number(document.getElementById("limit").value || "200");
                    limit = Math.max(1, Math.min(2000, value));
                    refresh().catch(console.error);
                  });

                  setInterval(() => refresh().catch(console.error), 2000);
                  refresh().catch(console.error);
                </script>
                </body>
                </html>
                """;
    }

    public record StartIngestionResponse(String jobId, com.pnomeer.pipeline.PipelineRunner.RunMode mode) {
    }

    public record IngestionJobResponse(
            String jobId,
            IngestionJobStatus status,
            String fileName,
            com.pnomeer.pipeline.PipelineRunner.RunMode runMode,
            long createdAtEpochMs,
            long updatedAtEpochMs,
            long startedAtEpochMs,
            long completedAtEpochMs,
            long firstProgressAtEpochMs,
            int producedCount,
            int insertedCount,
            int validationErrorCount,
            java.util.Map<String, Long> errorSummary,
            String failureMessage,
            com.pnomeer.pipeline.PipelineRunner.ProgressSnapshot latestProgress) {
    }

    public record IngestionJobsResponse(java.util.List<IngestionJobResponse> jobs) {
    }

    public record IngestionTimelineResponse(
            String jobId,
            IngestionJobStatus status,
            java.util.List<com.pnomeer.pipeline.PipelineRunner.ProgressSnapshot> snapshots) {
    }

    public record DbCountResponse(int totalRows) {
    }

    public record DbRowsResponse(int limit, java.util.List<DbRow> rows) {
    }

    public record DbRow(long id, String col1, String col2, int col3) {
    }
}
