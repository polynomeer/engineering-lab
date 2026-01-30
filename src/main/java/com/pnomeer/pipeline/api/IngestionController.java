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
                state.getProducedCount(),
                state.getInsertedCount(),
                state.getValidationErrorCount(),
                state.getErrorSummary(),
                state.getFailureMessage(),
                state.getLatestProgress());
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

    public record StartIngestionResponse(String jobId) {
    }

    public record IngestionJobResponse(
            String jobId,
            IngestionJobStatus status,
            int producedCount,
            int insertedCount,
            int validationErrorCount,
            java.util.Map<String, Long> errorSummary,
            String failureMessage,
            com.pnomeer.pipeline.PipelineRunner.ProgressSnapshot latestProgress) {
    }

    public record IngestionTimelineResponse(
            String jobId,
            IngestionJobStatus status,
            java.util.List<com.pnomeer.pipeline.PipelineRunner.ProgressSnapshot> snapshots) {
    }
}
