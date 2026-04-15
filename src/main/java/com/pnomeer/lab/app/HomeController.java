package com.pnomeer.lab.app;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {
    @GetMapping(value = {"/", "/ui"}, produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>Engineering Lab</title>
                  <style>
                    :root {
                      --bg: #282a36;
                      --panel: #1f2330;
                      --panel-2: #171a24;
                      --border: #44475a;
                      --text: #f8f8f2;
                      --muted: #6272a4;
                      --cyan: #8be9fd;
                      --green: #50fa7b;
                      --orange: #ffb86c;
                      --pink: #ff79c6;
                    }
                    body { font-family: ui-sans-serif, system-ui, -apple-system, sans-serif; margin: 0; background: radial-gradient(circle at top left, #31354a 0, var(--bg) 45%%); color: var(--text); }
                    .wrap { max-width: 1220px; margin: 24px auto 40px; padding: 0 16px; }
                    .hero { background: linear-gradient(135deg, rgba(139,233,253,0.12), rgba(255,121,198,0.08)); border: 1px solid var(--border); border-radius: 16px; padding: 20px; margin-bottom: 16px; }
                    .title { font-size: 32px; font-weight: 800; margin: 0; }
                    .sub { color: var(--muted); font-size: 13px; margin-top: 8px; max-width: 780px; line-height: 1.5; }
                    .stats { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 12px; margin-bottom: 16px; }
                    .card { background: var(--panel); border: 1px solid var(--border); border-radius: 14px; padding: 16px; }
                    .k { font-size: 12px; color: #bd93f9; text-transform: uppercase; letter-spacing: .06em; }
                    .v { font-size: 28px; font-weight: 800; margin-top: 6px; }
                    .grid { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 14px; }
                    .section { background: var(--panel); border: 1px solid var(--border); border-radius: 16px; padding: 16px; min-height: 230px; position: relative; overflow: hidden; }
                    .section::after { content: ""; position: absolute; right: -30px; bottom: -30px; width: 120px; height: 120px; border-radius: 999px; opacity: 0.12; }
                    .pipeline::after { background: var(--cyan); }
                    .experiment::after { background: var(--green); }
                    .iolab::after { background: var(--orange); }
                    .section h2 { margin: 0; font-size: 22px; }
                    .section p { color: var(--muted); font-size: 13px; line-height: 1.5; margin: 8px 0 14px; max-width: 32ch; }
                    .mini { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 10px; margin-bottom: 16px; }
                    .mini .item { background: var(--panel-2); border: 1px solid var(--border); border-radius: 10px; padding: 10px; }
                    .mini .item .label { color: var(--muted); font-size: 11px; }
                    .mini .item .value { margin-top: 6px; font-size: 20px; font-weight: 800; }
                    .links { display: flex; flex-wrap: wrap; gap: 10px; }
                    .links a { text-decoration: none; color: var(--text); background: var(--panel-2); border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; font-size: 13px; }
                    .links a:hover { border-color: var(--cyan); transform: translateY(-1px); }
                    @media (max-width: 980px) { .grid, .stats { grid-template-columns: 1fr; } }
                  </style>
                </head>
                <body>
                <div class="wrap">
                  <div class="hero">
                    <h1 class="title">Engineering Lab</h1>
                    <div class="sub">
                      One place to run and inspect pipeline ingestion, shared experiments, and IO comparison labs.
                      Live counts below are aggregated from the current in-memory job stores.
                    </div>
                  </div>

                  <div class="stats">
                    <div class="card"><div class="k">Pipeline Jobs</div><div class="v" id="pipelineCount">0</div></div>
                    <div class="card"><div class="k">Experiment Jobs</div><div class="v" id="experimentCount">0</div></div>
                    <div class="card"><div class="k">Running Total</div><div class="v" id="runningTotal">0</div></div>
                    <div class="card"><div class="k">Succeeded Total</div><div class="v" id="succeededTotal">0</div></div>
                  </div>

                  <div class="grid">
                    <section class="section pipeline">
                      <h2>Pipeline Lab</h2>
                      <p>Excel ingestion with backpressure, worker pools, batch inserts, and detailed runtime telemetry.</p>
                      <div class="mini">
                        <div class="item"><div class="label">Running</div><div class="value" id="pipelineRunning">0</div></div>
                        <div class="item"><div class="label">Inserted</div><div class="value" id="pipelineInserted">0</div></div>
                      </div>
                      <div class="links">
                        <a href="/ingest/ui">Open pipeline dashboard</a>
                        <a href="/ingest/ui/db">Open DB view</a>
                      </div>
                    </section>

                    <section class="section experiment">
                      <h2>Experiment Jobs</h2>
                      <p>Shared experiment runner for queue contention and future benchmark families using common contracts.</p>
                      <div class="mini">
                        <div class="item"><div class="label">Running</div><div class="value" id="experimentRunning">0</div></div>
                        <div class="item"><div class="label">Failed</div><div class="value" id="experimentFailed">0</div></div>
                      </div>
                      <div class="links">
                        <a href="/experiments/ui">Open experiment dashboard</a>
                      </div>
                    </section>

                    <section class="section iolab">
                      <h2>IO Lab</h2>
                      <p>Compare Spring virtual-thread and reactive endpoints, plus standalone blocking and selector servers.</p>
                      <div class="mini">
                        <div class="item"><div class="label">Virtual endpoint</div><div class="value">/lab/io/virtual-thread/echo</div></div>
                        <div class="item"><div class="label">Reactive endpoint</div><div class="value">/lab/io/reactive/echo</div></div>
                      </div>
                      <div class="links">
                        <a href="/lab/io/ui">Open IO comparison</a>
                      </div>
                    </section>
                  </div>
                </div>

                <script>
                  async function refresh() {
                    const [pipelineRes, experimentRes] = await Promise.all([
                      fetch("/ingest/jobs"),
                      fetch("/experiments/jobs")
                    ]);
                    if (!pipelineRes.ok || !experimentRes.ok) return;
                    const pipelinePayload = await pipelineRes.json();
                    const experimentPayload = await experimentRes.json();
                    const pipelineJobs = pipelinePayload.jobs || [];
                    const experimentJobs = experimentPayload.jobs || [];

                    const pipelineRunning = pipelineJobs.filter(j => j.status === "RUNNING").length;
                    const pipelineSucceeded = pipelineJobs.filter(j => j.status === "SUCCEEDED").length;
                    const experimentRunning = experimentJobs.filter(j => j.status === "RUNNING").length;
                    const experimentSucceeded = experimentJobs.filter(j => j.status === "SUCCEEDED").length;
                    const experimentFailed = experimentJobs.filter(j => j.status === "FAILED").length;
                    const inserted = pipelineJobs.reduce((sum, job) => sum + (job.insertedCount || 0), 0);

                    document.getElementById("pipelineCount").textContent = pipelineJobs.length;
                    document.getElementById("experimentCount").textContent = experimentJobs.length;
                    document.getElementById("runningTotal").textContent = pipelineRunning + experimentRunning;
                    document.getElementById("succeededTotal").textContent = pipelineSucceeded + experimentSucceeded;
                    document.getElementById("pipelineRunning").textContent = pipelineRunning;
                    document.getElementById("pipelineInserted").textContent = inserted;
                    document.getElementById("experimentRunning").textContent = experimentRunning;
                    document.getElementById("experimentFailed").textContent = experimentFailed;
                  }

                  setInterval(() => refresh().catch(console.error), 1200);
                  refresh().catch(console.error);
                </script>
                </body>
                </html>
                """;
    }
}
