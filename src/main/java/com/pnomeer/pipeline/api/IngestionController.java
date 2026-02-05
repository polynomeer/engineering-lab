package com.pnomeer.pipeline.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartIngestionResponse startExcelIngestion(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must not be empty");
        }
        return new StartIngestionResponse(ingestionService.startJob(file));
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
                state.getCreatedAtEpochMs(),
                state.getUpdatedAtEpochMs(),
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
                        state.getCreatedAtEpochMs(),
                        state.getUpdatedAtEpochMs(),
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
                    <div class="small">rawQueue: <span id="rawQ">0</span>, mappedQueue: <span id="mappedQ">0</span></div>
                  </div>

                  <div class="card">
                    <div class="k">Throughput (rows/sec)</div>
                    <svg id="rateChart" viewBox="0 0 1000 180" preserveAspectRatio="none"></svg>
                    <div class="small">produced: <span id="pr">0</span>, mapped: <span id="mr">0</span>, inserted: <span id="ir">0</span></div>
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

                  function renderCharts() {
                    const qRaw = timeline.map(s => s.rawQueueSize);
                    const qMapped = timeline.map(s => s.mappedQueueSize);
                    const rP = timeline.map(s => s.producedRatePerSec);
                    const rM = timeline.map(s => s.mappedRatePerSec);
                    const rI = timeline.map(s => s.insertedRatePerSec);

                    const qMax = Math.max(1, ...qRaw, ...qMapped);
                    const rMax = Math.max(1, ...rP, ...rM, ...rI);

                    document.getElementById("queueChart").innerHTML =
                      toPath(qRaw, qMax, "#8be9fd") + toPath(qMapped, qMax, "#ffb86c");
                    document.getElementById("rateChart").innerHTML =
                      toPath(rP, rMax, "#8be9fd") + toPath(rM, rMax, "#ffb86c") + toPath(rI, rMax, "#50fa7b");
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
                    document.getElementById("produced").textContent = job.producedCount;
                    document.getElementById("inserted").textContent = job.insertedCount;
                    document.getElementById("errors").textContent = job.validationErrorCount;

                    const latest = job.latestProgress || {};
                    document.getElementById("mapped").textContent = latest.mappedCount || 0;
                    document.getElementById("rawQ").textContent = latest.rawQueueSize || 0;
                    document.getElementById("mappedQ").textContent = latest.mappedQueueSize || 0;
                    document.getElementById("pr").textContent = latest.producedRatePerSec || 0;
                    document.getElementById("mr").textContent = latest.mappedRatePerSec || 0;
                    document.getElementById("ir").textContent = latest.insertedRatePerSec || 0;

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
                        <div class="row"><span>produced / inserted / errors</span><span>${j.producedCount} / ${j.insertedCount} / ${j.validationErrorCount}</span></div>
                        <div class="row"><span>queues raw|mapped</span><span>${latest.rawQueueSize || 0} | ${latest.mappedQueueSize || 0}</span></div>
                        <div class="row"><span>rate p|m|i</span><span>${latest.producedRatePerSec || 0} | ${latest.mappedRatePerSec || 0} | ${latest.insertedRatePerSec || 0}</span></div>
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

    public record StartIngestionResponse(String jobId) {
    }

    public record IngestionJobResponse(
            String jobId,
            IngestionJobStatus status,
            String fileName,
            long createdAtEpochMs,
            long updatedAtEpochMs,
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
}
