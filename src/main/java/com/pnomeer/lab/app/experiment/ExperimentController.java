package com.pnomeer.lab.app.experiment;

import com.pnomeer.lab.core.ExperimentStatus;
import com.pnomeer.lab.experiments.concurrency.QueueContentionScenario;
import com.pnomeer.lab.experiments.io.IoEndpointComparisonScenario;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/experiments")
public class ExperimentController {
    private final ExperimentJobService experimentJobService;

    public ExperimentController(ExperimentJobService experimentJobService) {
        this.experimentJobService = experimentJobService;
    }

    @PostMapping("/concurrency/queue-contention")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartExperimentResponse startQueueContention(@RequestBody QueueContentionRequest request) {
        QueueContentionScenario scenario = new QueueContentionScenario(
                request.scenarioId() == null || request.scenarioId().isBlank() ? "queue-contention" : request.scenarioId(),
                request.queueCapacity(),
                request.producerThreads(),
                request.consumerThreads(),
                request.itemsPerProducer(),
                request.consumerDelayMs());
        return new StartExperimentResponse(
                experimentJobService.startQueueContentionJob(scenario),
                "concurrency.queue-contention",
                scenario.scenarioId());
    }

    @PostMapping("/io/endpoint-comparison")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartExperimentResponse startIoEndpointComparison(@RequestBody IoEndpointComparisonRequest request) {
        IoEndpointComparisonScenario scenario = new IoEndpointComparisonScenario(
                request.scenarioId() == null || request.scenarioId().isBlank() ? "io-endpoint-comparison" : request.scenarioId(),
                request.requests(),
                request.delayMs(),
                request.pinning(),
                request.pinDelayMs());
        return new StartExperimentResponse(
                experimentJobService.startIoEndpointComparisonJob(scenario),
                "io.endpoint-comparison",
                scenario.scenarioId());
    }

    @GetMapping("/jobs")
    public ExperimentJobsResponse listJobs() {
        List<ExperimentJobResponse> jobs = experimentJobService.listJobs().stream()
                .map(this::toResponse)
                .toList();
        return new ExperimentJobsResponse(jobs);
    }

    @GetMapping("/jobs/{jobId}")
    public ExperimentJobResponse getJob(@PathVariable String jobId) {
        ExperimentJobState state = experimentJobService.getJob(jobId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found");
        }
        return toResponse(state);
    }

    @GetMapping("/jobs/{jobId}/metrics")
    public ExperimentMetricTimelineResponse getJobMetrics(@PathVariable String jobId) {
        ExperimentJobState state = experimentJobService.getJob(jobId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found");
        }
        return new ExperimentMetricTimelineResponse(
                state.getJobId(),
                state.getExperimentType(),
                state.getScenarioId(),
                state.getTimeline());
    }

    @GetMapping(value = "/ui", produces = MediaType.TEXT_HTML_VALUE)
    public String getExperimentDashboard() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>Engineering Lab Experiments</title>
                  <style>
                    body { font-family: ui-sans-serif, system-ui, -apple-system, sans-serif; margin: 0; background: #282a36; color: #f8f8f2; }
                    .wrap { max-width: 1180px; margin: 24px auto; padding: 0 16px; }
                    .card { background: #1f2330; border: 1px solid #44475a; border-radius: 12px; padding: 16px; margin-bottom: 14px; }
                    .title { font-size: 28px; font-weight: 700; margin: 0; }
                    .sub { font-size: 12px; color: #6272a4; margin-top: 6px; }
                    .grid { display: grid; grid-template-columns: 1.2fr 1fr; gap: 14px; }
                    .form-grid { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 10px; }
                    .toolbar { display: grid; grid-template-columns: 1fr 200px 200px; gap: 10px; align-items: end; }
                    label { display: block; font-size: 12px; color: #bd93f9; margin-bottom: 6px; }
                    input, select { width: 100%%; box-sizing: border-box; background: #171a24; color: #f8f8f2; border: 1px solid #44475a; border-radius: 8px; padding: 10px; }
                    button { background: linear-gradient(90deg, #8be9fd, #50fa7b); color: #10131c; border: 0; border-radius: 10px; padding: 10px 14px; font-weight: 700; cursor: pointer; }
                    .stats { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 10px; }
                    .k { font-size: 12px; color: #bd93f9; }
                    .v { font-size: 24px; font-weight: 700; margin-top: 4px; }
                    .jobs { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 12px; }
                    .job { background: #171a24; border: 1px solid #44475a; border-radius: 10px; padding: 12px; }
                    .row { display: flex; justify-content: space-between; gap: 10px; font-size: 12px; margin-top: 8px; }
                    .comparison { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 10px; }
                    .metric-box { background: #171a24; border: 1px solid #44475a; border-radius: 10px; padding: 12px; }
                    .badge { font-size: 11px; border-radius: 999px; padding: 3px 8px; border: 1px solid; font-weight: 700; }
                    .RUNNING { color: #ffb86c; border-color: #ffb86c; }
                    .SUCCEEDED { color: #50fa7b; border-color: #50fa7b; }
                    .FAILED { color: #ff5555; border-color: #ff5555; }
                    .link a { color: #8be9fd; text-decoration: none; }
                    @media (max-width: 960px) { .grid, .jobs, .stats, .form-grid, .toolbar, .comparison { grid-template-columns: 1fr; } }
                  </style>
                </head>
                <body>
                <div class="wrap">
                  <div class="card">
                    <h1 class="title">Engineering Lab</h1>
                    <div class="sub">Run shared experiments and inspect generic metrics live</div>
                  </div>

                  <div class="grid">
                    <div class="card">
                      <div class="k">Start Queue Contention Experiment</div>
                      <div class="form-grid" style="margin-top:12px;">
                        <div><label for="scenarioId">Scenario ID</label><input id="scenarioId" value="queue-contention-demo" /></div>
                        <div><label for="queueCapacity">Queue Capacity</label><input id="queueCapacity" type="number" value="2" min="1" /></div>
                        <div><label for="producerThreads">Producer Threads</label><input id="producerThreads" type="number" value="2" min="1" /></div>
                        <div><label for="consumerThreads">Consumer Threads</label><input id="consumerThreads" type="number" value="1" min="1" /></div>
                        <div><label for="itemsPerProducer">Items / Producer</label><input id="itemsPerProducer" type="number" value="100" min="1" /></div>
                        <div><label for="consumerDelayMs">Consumer Delay (ms)</label><input id="consumerDelayMs" type="number" value="2" min="0" /></div>
                      </div>
                      <div style="margin-top:12px;"><button id="runQueueBtn">Run Queue Experiment</button></div>
                    </div>

                    <div class="card">
                      <div class="k">Start IO Endpoint Comparison</div>
                      <div class="form-grid" style="margin-top:12px;">
                        <div><label for="ioScenarioId">Scenario ID</label><input id="ioScenarioId" value="io-comparison-demo" /></div>
                        <div><label for="ioRequests">Requests / Mode</label><input id="ioRequests" type="number" value="12" min="1" /></div>
                        <div><label for="ioDelayMs">Delay (ms)</label><input id="ioDelayMs" type="number" value="20" min="0" /></div>
                        <div><label for="ioPinning">Pinning (0/1)</label><input id="ioPinning" type="number" value="0" min="0" max="1" /></div>
                        <div><label for="ioPinDelayMs">Pin Delay (ms)</label><input id="ioPinDelayMs" type="number" value="20" min="0" /></div>
                      </div>
                      <div style="margin-top:12px;"><button id="runIoBtn">Run IO Experiment</button></div>
                      <div class="sub" id="runResult">No experiment started yet.</div>
                    </div>

                    <div class="card stats">
                      <div><div class="k">Running</div><div class="v" id="running">0</div></div>
                      <div><div class="k">Succeeded</div><div class="v" id="succeeded">0</div></div>
                      <div><div class="k">Failed</div><div class="v" id="failed">0</div></div>
                      <div><div class="k">Total Jobs</div><div class="v" id="total">0</div></div>
                    </div>
                  </div>

                  <div class="card">
                    <div class="k">Experiment Browser</div>
                    <div class="toolbar" style="margin-top:12px;">
                      <div>
                        <label for="jobFilter">Experiment Type Filter</label>
                        <select id="jobFilter">
                          <option value="all">All experiments</option>
                          <option value="concurrency.queue-contention">Queue contention</option>
                          <option value="io.endpoint-comparison">IO endpoint comparison</option>
                        </select>
                      </div>
                      <div>
                        <label for="compareA">Compare A</label>
                        <select id="compareA"></select>
                      </div>
                      <div>
                        <label for="compareB">Compare B</label>
                        <select id="compareB"></select>
                      </div>
                    </div>
                  </div>

                  <div class="card">
                    <div class="k">Comparison View</div>
                    <div class="sub">Compares the latest succeeded jobs using a normalized summary.</div>
                    <div class="comparison" style="margin-top:12px;">
                      <div class="metric-box">
                        <div class="k">Job A</div>
                        <div class="v" id="compareAType">-</div>
                        <div class="sub" id="compareAScenario">-</div>
                      </div>
                      <div class="metric-box">
                        <div class="k">Job B</div>
                        <div class="v" id="compareBType">-</div>
                        <div class="sub" id="compareBScenario">-</div>
                      </div>
                      <div class="metric-box">
                        <div class="k">Delta Summary</div>
                        <div class="sub" id="compareDelta">Pick two succeeded jobs.</div>
                      </div>
                    </div>
                  </div>

                  <div id="jobs" class="jobs"></div>
                </div>

                <script>
                  let allJobs = [];

                  function esc(value) {
                    return String(value ?? "").replaceAll("&","&amp;").replaceAll("<","&lt;").replaceAll(">","&gt;");
                  }

                  function summarizeJob(job) {
                    if (job.experimentType === "io.endpoint-comparison") {
                      return {
                        primary: job.gauges?.["throughput.totalRequestsPerSec"] ?? 0,
                        primaryLabel: "total throughput",
                        secondary: job.gauges?.["virtual.avgLatencyMs"] ?? 0,
                        secondaryLabel: "virtual avg latency",
                        unitPrimary: "/s",
                        unitSecondary: "ms"
                      };
                    }
                    return {
                      primary: job.gauges?.["throughput.itemsPerSec"] ?? 0,
                      primaryLabel: "throughput",
                      secondary: job.gauges?.["queue.avgEnqueueWaitMs"] ?? 0,
                      secondaryLabel: "avg enqueue wait",
                      unitPrimary: "/s",
                      unitSecondary: "ms"
                    };
                  }

                  function formatMetric(value, unit) {
                    return `${Number(value ?? 0).toFixed(2)}${unit}`;
                  }

                  function comparisonLabel(summaryA, summaryB) {
                    const primaryDelta = summaryA.primary - summaryB.primary;
                    const secondaryDelta = summaryA.secondary - summaryB.secondary;
                    return `${summaryA.primaryLabel}: ${formatMetric(summaryA.primary, summaryA.unitPrimary)} vs ${formatMetric(summaryB.primary, summaryB.unitPrimary)} | delta ${primaryDelta >= 0 ? "+" : ""}${primaryDelta.toFixed(2)}${summaryA.unitPrimary}
${summaryA.secondaryLabel}: ${formatMetric(summaryA.secondary, summaryA.unitSecondary)} vs ${formatMetric(summaryB.secondary, summaryB.unitSecondary)} | delta ${secondaryDelta >= 0 ? "+" : ""}${secondaryDelta.toFixed(2)}${summaryA.unitSecondary}`;
                  }

                  function populateComparisonSelectors(jobs) {
                    const succeeded = jobs.filter(job => job.status === "SUCCEEDED");
                    const options = succeeded.map(job =>
                      `<option value="${esc(job.jobId)}">${esc(job.experimentType)} :: ${esc(job.scenarioId)}</option>`
                    ).join("");
                    document.getElementById("compareA").innerHTML = `<option value="">Select job</option>${options}`;
                    document.getElementById("compareB").innerHTML = `<option value="">Select job</option>${options}`;
                    if (succeeded[0] && !document.getElementById("compareA").value) {
                      document.getElementById("compareA").value = succeeded[0].jobId;
                    }
                    if (succeeded[1] && !document.getElementById("compareB").value) {
                      document.getElementById("compareB").value = succeeded[1].jobId;
                    }
                  }

                  function renderComparison() {
                    const jobA = allJobs.find(job => job.jobId === document.getElementById("compareA").value);
                    const jobB = allJobs.find(job => job.jobId === document.getElementById("compareB").value);
                    document.getElementById("compareAType").textContent = jobA?.experimentType ?? "-";
                    document.getElementById("compareAScenario").textContent = jobA?.scenarioId ?? "-";
                    document.getElementById("compareBType").textContent = jobB?.experimentType ?? "-";
                    document.getElementById("compareBScenario").textContent = jobB?.scenarioId ?? "-";
                    if (!jobA || !jobB) {
                      document.getElementById("compareDelta").textContent = "Pick two succeeded jobs.";
                      return;
                    }
                    const summaryA = summarizeJob(jobA);
                    const summaryB = summarizeJob(jobB);
                    document.getElementById("compareDelta").textContent = comparisonLabel(summaryA, summaryB);
                  }

                  function renderJob(job) {
                    if (job.experimentType === "io.endpoint-comparison") {
                      return `
                        <div class="job">
                          <div class="row"><strong>${esc(job.experimentType)}</strong><span class="badge ${job.status}">${job.status}</span></div>
                          <div class="row"><span>jobId</span><span>${esc(job.jobId)}</span></div>
                          <div class="row"><span>scenario</span><span>${esc(job.scenarioId)}</span></div>
                          <div class="row"><span>virtual done</span><span>${job.counters?.["requests.virtualCompleted"] ?? 0}</span></div>
                          <div class="row"><span>reactive done</span><span>${job.counters?.["requests.reactiveCompleted"] ?? 0}</span></div>
                          <div class="row"><span>virtual avg</span><span>${((job.gauges?.["virtual.avgLatencyMs"] ?? 0)).toFixed(2)}ms</span></div>
                          <div class="row"><span>reactive avg</span><span>${((job.gauges?.["reactive.avgLatencyMs"] ?? 0)).toFixed(2)}ms</span></div>
                          <div class="row"><span>total throughput</span><span>${((job.gauges?.["throughput.totalRequestsPerSec"] ?? 0)).toFixed(1)}/s</span></div>
                          <div class="row"><span>winner</span><span>${esc(job.details?.["winner"] ?? "-")}</span></div>
                          <div class="row link"><span>detail</span><a href="/experiments/ui/${job.jobId}" target="_blank">open metrics</a></div>
                        </div>
                      `;
                    }
                    return `
                      <div class="job">
                        <div class="row"><strong>${esc(job.experimentType)}</strong><span class="badge ${job.status}">${job.status}</span></div>
                        <div class="row"><span>jobId</span><span>${esc(job.jobId)}</span></div>
                        <div class="row"><span>scenario</span><span>${esc(job.scenarioId)}</span></div>
                        <div class="row"><span>produced</span><span>${job.counters?.["items.produced"] ?? 0}</span></div>
                        <div class="row"><span>consumed</span><span>${job.counters?.["items.consumed"] ?? 0}</span></div>
                        <div class="row"><span>blocking events</span><span>${job.counters?.["blocking.events"] ?? 0}</span></div>
                        <div class="row"><span>max depth</span><span>${(job.gauges?.["queue.maxDepth"] ?? 0).toFixed ? job.gauges["queue.maxDepth"].toFixed(1) : job.gauges?.["queue.maxDepth"] ?? 0}</span></div>
                        <div class="row"><span>avg enqueue wait</span><span>${((job.gauges?.["queue.avgEnqueueWaitMs"] ?? 0)).toFixed(2)}ms</span></div>
                        <div class="row"><span>throughput</span><span>${((job.gauges?.["throughput.itemsPerSec"] ?? 0)).toFixed(1)}/s</span></div>
                        <div class="row link"><span>detail</span><a href="/experiments/ui/${job.jobId}" target="_blank">open metrics</a></div>
                      </div>
                    `;
                  }

                  async function refreshJobs() {
                    const res = await fetch("/experiments/jobs");
                    if (!res.ok) return;
                    const data = await res.json();
                    allJobs = data.jobs || [];
                    const filter = document.getElementById("jobFilter").value;
                    const jobs = filter === "all" ? allJobs : allJobs.filter(job => job.experimentType === filter);
                    document.getElementById("running").textContent = jobs.filter(j => j.status === "RUNNING").length;
                    document.getElementById("succeeded").textContent = jobs.filter(j => j.status === "SUCCEEDED").length;
                    document.getElementById("failed").textContent = jobs.filter(j => j.status === "FAILED").length;
                    document.getElementById("total").textContent = allJobs.length;
                    document.getElementById("jobs").innerHTML = jobs.map(renderJob).join("");
                    populateComparisonSelectors(allJobs);
                    renderComparison();
                  }

                  async function startQueueExperiment() {
                    const body = {
                      scenarioId: document.getElementById("scenarioId").value,
                      queueCapacity: Number(document.getElementById("queueCapacity").value),
                      producerThreads: Number(document.getElementById("producerThreads").value),
                      consumerThreads: Number(document.getElementById("consumerThreads").value),
                      itemsPerProducer: Number(document.getElementById("itemsPerProducer").value),
                      consumerDelayMs: Number(document.getElementById("consumerDelayMs").value)
                    };
                    const res = await fetch("/experiments/concurrency/queue-contention", {
                      method: "POST",
                      headers: { "Content-Type": "application/json" },
                      body: JSON.stringify(body)
                    });
                    if (!res.ok) {
                      document.getElementById("runResult").textContent = "Failed to start experiment.";
                      return;
                    }
                    const data = await res.json();
                    document.getElementById("runResult").innerHTML = `Started <code>${esc(data.jobId)}</code>`;
                    refreshJobs().catch(console.error);
                  }

                  async function startIoExperiment() {
                    const body = {
                      scenarioId: document.getElementById("ioScenarioId").value,
                      requests: Number(document.getElementById("ioRequests").value),
                      delayMs: Number(document.getElementById("ioDelayMs").value),
                      pinning: Number(document.getElementById("ioPinning").value) === 1,
                      pinDelayMs: Number(document.getElementById("ioPinDelayMs").value)
                    };
                    const res = await fetch("/experiments/io/endpoint-comparison", {
                      method: "POST",
                      headers: { "Content-Type": "application/json" },
                      body: JSON.stringify(body)
                    });
                    if (!res.ok) {
                      document.getElementById("runResult").textContent = "Failed to start experiment.";
                      return;
                    }
                    const data = await res.json();
                    document.getElementById("runResult").innerHTML = `Started <code>${esc(data.jobId)}</code>`;
                    refreshJobs().catch(console.error);
                  }

                  document.getElementById("runQueueBtn").addEventListener("click", () => startQueueExperiment().catch(console.error));
                  document.getElementById("runIoBtn").addEventListener("click", () => startIoExperiment().catch(console.error));
                  document.getElementById("jobFilter").addEventListener("change", () => refreshJobs().catch(console.error));
                  document.getElementById("compareA").addEventListener("change", renderComparison);
                  document.getElementById("compareB").addEventListener("change", renderComparison);
                  setInterval(() => refreshJobs().catch(console.error), 1000);
                  refreshJobs().catch(console.error);
                </script>
                </body>
                </html>
                """;
    }

    @GetMapping(value = "/ui/{jobId}", produces = MediaType.TEXT_HTML_VALUE)
    public String getExperimentDetail(@PathVariable String jobId) {
        String safeJobId = HtmlUtils.htmlEscape(jobId);
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>Experiment %s</title>
                  <style>
                    body { font-family: ui-sans-serif, system-ui, -apple-system, sans-serif; margin: 0; background: #282a36; color: #f8f8f2; }
                    .wrap { max-width: 1080px; margin: 24px auto; padding: 0 16px; }
                    .card { background: #1f2330; border: 1px solid #44475a; border-radius: 12px; padding: 16px; margin-bottom: 14px; }
                    .k { font-size: 12px; color: #bd93f9; }
                    .v { font-size: 24px; font-weight: 700; margin-top: 4px; }
                    .grid { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 10px; }
                    svg { width: 100%%; height: 220px; background: #171a24; border-radius: 10px; border: 1px solid #44475a; }
                    .small { font-size: 12px; color: #6272a4; }
                    @media (max-width: 900px) { .grid { grid-template-columns: 1fr; } }
                  </style>
                </head>
                <body>
                <div class="wrap">
                  <div class="card">
                    <div class="small">Job ID: <code>%s</code></div>
                    <div class="small">Experiment: <span id="experimentType">-</span></div>
                    <div class="small">Scenario: <span id="scenarioId">-</span></div>
                    <div class="small">Status: <span id="status">RUNNING</span></div>
                  </div>

                  <div class="card grid">
                    <div><div class="k" id="card1Label">Metric A</div><div class="v" id="card1Value">0</div></div>
                    <div><div class="k" id="card2Label">Metric B</div><div class="v" id="card2Value">0</div></div>
                    <div><div class="k" id="card3Label">Metric C</div><div class="v" id="card3Value">0</div></div>
                    <div><div class="k" id="card4Label">Elapsed</div><div class="v" id="card4Value">0ms</div></div>
                  </div>

                  <div class="card">
                    <div class="k" id="chartTitle">Experiment Metrics</div>
                    <svg id="chart" viewBox="0 0 1000 220" preserveAspectRatio="none"></svg>
                    <div class="small">
                      <span id="metric1Label">metric-1</span>: <span id="metric1Value">0</span>,
                      <span id="metric2Label">metric-2</span>: <span id="metric2Value">0</span>,
                      <span id="metric3Label">metric-3</span>: <span id="metric3Value">0</span>,
                      <span id="metric4Label">metric-4</span>: <span id="metric4Value">0</span>
                    </div>
                  </div>
                </div>

                <script>
                  const jobId = "%s";
                  let snapshots = [];

                  function toPath(data, yMax, color) {
                    if (!data.length || yMax <= 0) return "";
                    const step = 1000 / Math.max(1, data.length - 1);
                    const pts = data.map((v, i) => {
                      const x = i * step;
                      const y = 210 - (Math.max(0, v) / yMax) * 190;
                      return `${x.toFixed(2)},${y.toFixed(2)}`;
                    }).join(" ");
                    return `<polyline points="${pts}" stroke="${color}" fill="none" stroke-width="2"/>`;
                  }

                  function metricConfig(experimentType) {
                    if (experimentType === "io.endpoint-comparison") {
                      return {
                        chartTitle: "IO Comparison Metrics",
                        cards: [
                          ["Virtual Done", job => job.counters?.["requests.virtualCompleted"] ?? 0],
                          ["Reactive Done", job => job.counters?.["requests.reactiveCompleted"] ?? 0],
                          ["Winner", job => job.details?.["winner"] ?? "-"],
                          ["Elapsed", job => `${Math.round(job.gauges?.["time.elapsedMs"] ?? 0)}ms`]
                        ],
                        footer: [
                          ["virtual avg", (job, latest) => `${((latest.gauges?.["virtual.avgLatencyMs"] ?? job.gauges?.["virtual.avgLatencyMs"] ?? 0)).toFixed(2)}ms`],
                          ["reactive avg", (job, latest) => `${((latest.gauges?.["reactive.avgLatencyMs"] ?? job.gauges?.["reactive.avgLatencyMs"] ?? 0)).toFixed(2)}ms`],
                          ["total throughput", (job, latest) => `${((latest.gauges?.["throughput.totalRequestsPerSec"] ?? job.gauges?.["throughput.totalRequestsPerSec"] ?? 0)).toFixed(1)}/s`],
                          ["total completed", (job, latest) => String(latest.counters?.["requests.totalCompleted"] ?? job.counters?.["requests.totalCompleted"] ?? 0)]
                        ],
                        series: [
                          { color: "#8be9fd", values: snapshots => snapshots.map(s => s.gauges?.["virtual.avgLatencyMs"] ?? 0) },
                          { color: "#50fa7b", values: snapshots => snapshots.map(s => s.gauges?.["reactive.avgLatencyMs"] ?? 0) },
                          { color: "#ffb86c", values: snapshots => snapshots.map(s => s.counters?.["requests.totalCompleted"] ?? 0) }
                        ]
                      };
                    }
                    return {
                      chartTitle: "Queue Metrics",
                      cards: [
                        ["Produced", job => job.counters?.["items.produced"] ?? 0],
                        ["Consumed", job => job.counters?.["items.consumed"] ?? 0],
                        ["Blocking Events", job => job.counters?.["blocking.events"] ?? 0],
                        ["Elapsed", job => `${Math.round(job.gauges?.["time.elapsedMs"] ?? 0)}ms`]
                      ],
                      footer: [
                        ["depth", (job, latest) => String(Math.round(latest.gauges?.["queue.depth"] ?? 0))],
                        ["max depth", (job, latest) => String(Math.round(job.gauges?.["queue.maxDepth"] ?? 0))],
                        ["avg enqueue wait", (job, latest) => `${((job.gauges?.["queue.avgEnqueueWaitMs"] ?? 0)).toFixed(2)}ms`],
                        ["throughput", (job, latest) => `${((job.gauges?.["throughput.itemsPerSec"] ?? 0)).toFixed(1)}/s`]
                      ],
                      series: [
                        { color: "#8be9fd", values: snapshots => snapshots.map(s => s.gauges?.["queue.depth"] ?? 0) },
                        { color: "#ffb86c", values: snapshots => snapshots.map(s => s.gauges?.["queue.maxDepth"] ?? 0) },
                        { color: "#50fa7b", values: snapshots => snapshots.map(s => s.counters?.["items.produced"] ?? 0) }
                      ]
                    };
                  }

                  async function refresh() {
                    const [jobRes, metricRes] = await Promise.all([
                      fetch(`/experiments/jobs/${jobId}`),
                      fetch(`/experiments/jobs/${jobId}/metrics`)
                    ]);
                    if (!jobRes.ok || !metricRes.ok) return;
                    const job = await jobRes.json();
                    const metricPayload = await metricRes.json();
                    snapshots = metricPayload.snapshots || [];

                    document.getElementById("experimentType").textContent = job.experimentType;
                    document.getElementById("scenarioId").textContent = job.scenarioId;
                    document.getElementById("status").textContent = job.status;
                    const latest = snapshots[snapshots.length - 1] || { gauges: {}, counters: {} };
                    const config = metricConfig(job.experimentType);
                    document.getElementById("chartTitle").textContent = config.chartTitle;
                    config.cards.forEach((entry, index) => {
                      document.getElementById(`card${index + 1}Label`).textContent = entry[0];
                      document.getElementById(`card${index + 1}Value`).textContent = entry[1](job);
                    });
                    config.footer.forEach((entry, index) => {
                      document.getElementById(`metric${index + 1}Label`).textContent = entry[0];
                      document.getElementById(`metric${index + 1}Value`).textContent = entry[1](job, latest);
                    });

                    const series = config.series.map(item => ({ color: item.color, values: item.values(snapshots) }));
                    const yMax = Math.max(1, ...series.flatMap(item => item.values));
                    document.getElementById("chart").innerHTML = series
                      .map(item => toPath(item.values, yMax, item.color))
                      .join("");
                  }

                  setInterval(() => refresh().catch(console.error), 1000);
                  refresh().catch(console.error);
                </script>
                </body>
                </html>
                """.formatted(safeJobId, safeJobId, safeJobId);
    }

    private ExperimentJobResponse toResponse(ExperimentJobState state) {
        return new ExperimentJobResponse(
                state.getJobId(),
                state.getExperimentType(),
                state.getScenarioId(),
                state.getStatus(),
                state.getCreatedAtEpochMs(),
                state.getUpdatedAtEpochMs(),
                state.getStartedAtEpochMs(),
                state.getCompletedAtEpochMs(),
                state.getFirstMetricAtEpochMs(),
                state.getFailureMessage(),
                state.getSummary().counters(),
                state.getSummary().gauges(),
                state.getSummary().details());
    }

    public record QueueContentionRequest(
            String scenarioId,
            int queueCapacity,
            int producerThreads,
            int consumerThreads,
            int itemsPerProducer,
            long consumerDelayMs) {
    }

    public record IoEndpointComparisonRequest(
            String scenarioId,
            int requests,
            long delayMs,
            boolean pinning,
            long pinDelayMs) {
    }

    public record StartExperimentResponse(String jobId, String experimentType, String scenarioId) {
    }

    public record ExperimentJobResponse(
            String jobId,
            String experimentType,
            String scenarioId,
            ExperimentStatus status,
            long createdAtEpochMs,
            long updatedAtEpochMs,
            long startedAtEpochMs,
            long completedAtEpochMs,
            long firstMetricAtEpochMs,
            String failureMessage,
            Map<String, Long> counters,
            Map<String, Double> gauges,
            Map<String, String> details) {
    }

    public record ExperimentJobsResponse(List<ExperimentJobResponse> jobs) {
    }

    public record ExperimentMetricTimelineResponse(
            String jobId,
            String experimentType,
            String scenarioId,
            List<com.pnomeer.lab.core.MetricPoint> snapshots) {
    }
}
