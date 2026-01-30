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
                    body { font-family: ui-sans-serif, system-ui, -apple-system, sans-serif; margin: 0; background: #0b1320; color: #e6edf7; }
                    .wrap { max-width: 1080px; margin: 24px auto; padding: 0 16px; }
                    .card { background: #121c2d; border: 1px solid #22314e; border-radius: 10px; padding: 14px; margin-bottom: 14px; }
                    .grid { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 10px; }
                    .k { font-size: 12px; color: #8ea2c9; }
                    .v { font-size: 22px; font-weight: 700; }
                    svg { width: 100%%; height: 180px; background: #0f1727; border-radius: 8px; border: 1px solid #243555; }
                    .small { font-size: 12px; color: #9eb2d6; }
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
                      toPath(qRaw, qMax, "#58a6ff") + toPath(qMapped, qMax, "#f2cc60");
                    document.getElementById("rateChart").innerHTML =
                      toPath(rP, rMax, "#58a6ff") + toPath(rM, rMax, "#f2cc60") + toPath(rI, rMax, "#66d9a0");
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
                    :root { --bg: #070c14; --panel: #101a2a; --line: #263a5e; --txt: #e8f0ff; --muted: #90a5cc; --ok: #63d38f; --run: #f6c358; --fail: #ff6b7d; }
                    body { margin: 0; font-family: ui-sans-serif, system-ui, -apple-system, sans-serif; color: var(--txt); background: radial-gradient(1200px 600px at 20%% -10%%, #1b2b47 0%%, var(--bg) 60%%); }
                    .wrap { max-width: 1200px; margin: 24px auto; padding: 0 16px 40px; }
                    h1 { margin: 0 0 6px; font-size: 28px; letter-spacing: 0.4px; }
                    .sub { color: var(--muted); margin-bottom: 16px; }
                    .statbar { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-bottom: 14px; }
                    .stat { background: color-mix(in srgb, var(--panel) 90%%, black); border: 1px solid var(--line); border-radius: 12px; padding: 10px; }
                    .k { color: var(--muted); font-size: 11px; text-transform: uppercase; letter-spacing: 0.7px; }
                    .v { font-size: 24px; font-weight: 700; }
                    .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
                    .job { background: linear-gradient(180deg, #12223a, #0d182a); border: 1px solid var(--line); border-radius: 14px; padding: 12px; box-shadow: 0 8px 24px rgba(0,0,0,0.25); animation: pulse 2.5s ease-in-out infinite; }
                    .job.done { animation: none; opacity: 0.95; }
                    .top { display: flex; justify-content: space-between; gap: 8px; align-items: center; }
                    .name { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 70%%; }
                    .badge { font-size: 11px; border-radius: 999px; padding: 3px 8px; font-weight: 700; }
                    .RUNNING { background: color-mix(in srgb, var(--run) 30%%, transparent); color: var(--run); border: 1px solid color-mix(in srgb, var(--run) 50%%, black); }
                    .SUCCEEDED { background: color-mix(in srgb, var(--ok) 25%%, transparent); color: var(--ok); border: 1px solid color-mix(in srgb, var(--ok) 50%%, black); }
                    .FAILED { background: color-mix(in srgb, var(--fail) 25%%, transparent); color: var(--fail); border: 1px solid color-mix(in srgb, var(--fail) 50%%, black); }
                    .row { display: flex; justify-content: space-between; font-size: 12px; margin-top: 7px; color: #cfe0ff; }
                    .bar { margin-top: 8px; background: #0a1322; border: 1px solid #203250; border-radius: 8px; height: 10px; overflow: hidden; }
                    .fill { height: 100%%; background: linear-gradient(90deg, #4fa2ff, #5fd398); width: 0%%; transition: width 0.3s ease; }
                    .link { margin-top: 8px; font-size: 12px; }
                    .link a { color: #8dc1ff; text-decoration: none; }
                    @keyframes pulse { 0%%,100%% { box-shadow: 0 8px 24px rgba(0,0,0,.22); } 50%% { box-shadow: 0 8px 32px rgba(31,80,170,.35); } }
                    @media (max-width: 900px) { .grid, .statbar { grid-template-columns: 1fr; } }
                  </style>
                </head>
                <body>
                <div class="wrap">
                  <h1>Ingestion Jobs</h1>
                  <div class="sub">Live dashboard auto-refreshes every second</div>
                  <div class="statbar">
                    <div class="stat"><div class="k">Running</div><div class="v" id="running">0</div></div>
                    <div class="stat"><div class="k">Succeeded</div><div class="v" id="succeeded">0</div></div>
                    <div class="stat"><div class="k">Failed</div><div class="v" id="failed">0</div></div>
                    <div class="stat"><div class="k">Total Inserted</div><div class="v" id="insertedTotal">0</div></div>
                  </div>
                  <div id="jobs" class="grid"></div>
                </div>
                <script>
                  function fmtTime(ts) { return new Date(ts).toLocaleTimeString(); }
                  function ratio(p, i, e) {
                    const denom = Math.max(1, p - e);
                    return Math.max(0, Math.min(100, Math.round((i / denom) * 100)));
                  }
                  function renderJob(j) {
                    const latest = j.latestProgress || {};
                    const doneClass = j.status === "RUNNING" ? "" : "done";
                    const failure = j.failureMessage ? `<div class="row"><span>failure</span><span>${j.failureMessage}</span></div>` : "";
                    return `
                      <div class="job ${doneClass}">
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
