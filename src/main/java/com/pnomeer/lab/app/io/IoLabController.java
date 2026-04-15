package com.pnomeer.lab.app.io;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/lab/io")
public class IoLabController {
    private static final Object PIN_LOCK = new Object();

    private final ExecutorService ioVirtualThreadExecutor;

    public IoLabController(@Qualifier("ioVirtualThreadExecutor") ExecutorService ioVirtualThreadExecutor) {
        this.ioVirtualThreadExecutor = ioVirtualThreadExecutor;
    }

    @GetMapping(value = "/virtual-thread/echo", produces = MediaType.TEXT_PLAIN_VALUE)
    public CompletableFuture<String> virtualThreadEcho(
            @RequestParam String msg,
            @RequestParam(defaultValue = "100") long delayMs,
            @RequestParam(defaultValue = "false") boolean pinning,
            @RequestParam(defaultValue = "100") long pinDelayMs) {
        long safeDelayMs = Math.max(0L, delayMs);
        long safePinDelayMs = Math.max(0L, pinDelayMs);
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (pinning) {
                    synchronized (PIN_LOCK) {
                        Thread.sleep(safePinDelayMs);
                    }
                }
                Thread.sleep(safeDelayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("virtual-thread echo interrupted", ex);
            }
            return "[virtual-thread] " + msg
                    + " | pinning=" + pinning
                    + " | thread=" + Thread.currentThread();
        }, ioVirtualThreadExecutor);
    }

    @GetMapping(value = "/reactive/echo", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> reactiveEcho(
            @RequestParam String msg,
            @RequestParam(defaultValue = "100") long delayMs) {
        long safeDelayMs = Math.max(0L, delayMs);
        return Mono.delay(Duration.ofMillis(safeDelayMs))
                .map(ignored -> "[reactive] " + msg + " | thread=" + Thread.currentThread());
    }

    @GetMapping(value = "/ui", produces = MediaType.TEXT_HTML_VALUE)
    public String ioLabDashboard(@RequestParam(defaultValue = "hello") String msg) {
        String safeMsg = HtmlUtils.htmlEscape(msg);
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>IO Lab</title>
                  <style>
                    body { font-family: ui-sans-serif, system-ui, -apple-system, sans-serif; margin: 0; background: #282a36; color: #f8f8f2; }
                    .wrap { max-width: 1120px; margin: 24px auto; padding: 0 16px; }
                    .card { background: #1f2330; border: 1px solid #44475a; border-radius: 12px; padding: 16px; margin-bottom: 14px; }
                    .title { font-size: 28px; font-weight: 700; margin: 0; }
                    .sub { font-size: 12px; color: #6272a4; margin-top: 6px; }
                    .grid { display: grid; grid-template-columns: 1.1fr 1fr; gap: 14px; }
                    .form-grid { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 10px; }
                    label { display: block; font-size: 12px; color: #bd93f9; margin-bottom: 6px; }
                    input { width: 100%%; box-sizing: border-box; background: #171a24; color: #f8f8f2; border: 1px solid #44475a; border-radius: 8px; padding: 10px; }
                    button { background: linear-gradient(90deg, #8be9fd, #50fa7b); color: #10131c; border: 0; border-radius: 10px; padding: 10px 14px; font-weight: 700; cursor: pointer; }
                    .stats { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 10px; }
                    .k { font-size: 12px; color: #bd93f9; }
                    .v { font-size: 22px; font-weight: 700; margin-top: 4px; }
                    svg { width: 100%%; height: 220px; background: #171a24; border-radius: 10px; border: 1px solid #44475a; }
                    .two { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 14px; }
                    .small { font-size: 12px; color: #6272a4; }
                    @media (max-width: 920px) { .grid, .two, .form-grid, .stats { grid-template-columns: 1fr; } }
                  </style>
                </head>
                <body>
                <div class="wrap">
                  <div class="card">
                    <h1 class="title">IO Lab</h1>
                    <div class="sub">Compare Spring virtual-thread and reactive endpoints with lightweight browser-driven load.</div>
                  </div>

                  <div class="grid">
                    <div class="card">
                      <div class="k">Benchmark Settings</div>
                      <div class="form-grid" style="margin-top:12px;">
                        <div><label for="msg">Message</label><input id="msg" value="%s" /></div>
                        <div><label for="delayMs">Delay (ms)</label><input id="delayMs" type="number" min="0" value="100" /></div>
                        <div><label for="requests">Requests</label><input id="requests" type="number" min="1" value="30" /></div>
                        <div><label for="pinning">Pinning (0/1)</label><input id="pinning" type="number" min="0" max="1" value="0" /></div>
                        <div><label for="pinDelayMs">Pin Delay (ms)</label><input id="pinDelayMs" type="number" min="0" value="100" /></div>
                      </div>
                      <div style="margin-top: 12px;"><button id="runBtn">Run Comparison</button></div>
                      <div class="sub" id="runState">Idle</div>
                    </div>
                    <div class="card stats">
                      <div><div class="k">Virtual Avg</div><div class="v" id="virtualAvg">0.0ms</div></div>
                      <div><div class="k">Reactive Avg</div><div class="v" id="reactiveAvg">0.0ms</div></div>
                      <div><div class="k">Faster</div><div class="v" id="winner">-</div></div>
                    </div>
                  </div>

                  <div class="two">
                    <div class="card">
                      <div class="k">Virtual-Thread Endpoint</div>
                      <div class="small">`/lab/io/virtual-thread/echo`</div>
                      <div class="stats" style="margin-top:12px;">
                        <div><div class="k">Avg</div><div class="v" id="virtualAvgCard">0.0ms</div></div>
                        <div><div class="k">Total</div><div class="v" id="virtualTotal">0ms</div></div>
                        <div><div class="k">Req/s</div><div class="v" id="virtualRps">0.0</div></div>
                      </div>
                    </div>
                    <div class="card">
                      <div class="k">Reactive Endpoint</div>
                      <div class="small">`/lab/io/reactive/echo`</div>
                      <div class="stats" style="margin-top:12px;">
                        <div><div class="k">Avg</div><div class="v" id="reactiveAvgCard">0.0ms</div></div>
                        <div><div class="k">Total</div><div class="v" id="reactiveTotal">0ms</div></div>
                        <div><div class="k">Req/s</div><div class="v" id="reactiveRps">0.0</div></div>
                      </div>
                    </div>
                  </div>

                  <div class="card">
                    <div class="k">Latency Samples</div>
                    <svg id="chart" viewBox="0 0 1000 220" preserveAspectRatio="none"></svg>
                    <div class="small">cyan: virtual-thread, green: reactive</div>
                  </div>
                </div>

                <script>
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

                  async function runSeries(path, requests, delayMs, msg, extraParams = "") {
                    const samples = [];
                    const started = performance.now();
                    for (let i = 0; i < requests; i++) {
                      const t1 = performance.now();
                      const res = await fetch(`${path}?msg=${encodeURIComponent(msg)}&delayMs=${delayMs}${extraParams}`);
                      await res.text();
                      samples.push(performance.now() - t1);
                    }
                    const totalMs = performance.now() - started;
                    const avgMs = samples.reduce((sum, value) => sum + value, 0) / Math.max(1, samples.length);
                    const rps = requests / Math.max(0.001, totalMs / 1000);
                    return { samples, totalMs, avgMs, rps };
                  }

                  function renderMetrics(prefix, metrics) {
                    document.getElementById(`${prefix}AvgCard`).textContent = `${metrics.avgMs.toFixed(1)}ms`;
                    document.getElementById(`${prefix}Total`).textContent = `${Math.round(metrics.totalMs)}ms`;
                    document.getElementById(`${prefix}Rps`).textContent = metrics.rps.toFixed(1);
                  }

                  async function runComparison() {
                    const msg = document.getElementById("msg").value;
                    const delayMs = Number(document.getElementById("delayMs").value);
                    const requests = Number(document.getElementById("requests").value);
                    const pinning = Number(document.getElementById("pinning").value) === 1;
                    const pinDelayMs = Number(document.getElementById("pinDelayMs").value);
                    document.getElementById("runState").textContent = "Running comparison...";

                    const [virtualMetrics, reactiveMetrics] = await Promise.all([
                      runSeries("/lab/io/virtual-thread/echo", requests, delayMs, msg, `&pinning=${pinning}&pinDelayMs=${pinDelayMs}`),
                      runSeries("/lab/io/reactive/echo", requests, delayMs, msg)
                    ]);

                    document.getElementById("virtualAvg").textContent = `${virtualMetrics.avgMs.toFixed(1)}ms`;
                    document.getElementById("reactiveAvg").textContent = `${reactiveMetrics.avgMs.toFixed(1)}ms`;
                    renderMetrics("virtual", virtualMetrics);
                    renderMetrics("reactive", reactiveMetrics);

                    const winner = virtualMetrics.avgMs < reactiveMetrics.avgMs ? "Virtual" :
                      reactiveMetrics.avgMs < virtualMetrics.avgMs ? "Reactive" : "Tie";
                    document.getElementById("winner").textContent = winner;

                    const yMax = Math.max(1, ...virtualMetrics.samples, ...reactiveMetrics.samples);
                    document.getElementById("chart").innerHTML =
                      toPath(virtualMetrics.samples, yMax, "#8be9fd") +
                      toPath(reactiveMetrics.samples, yMax, "#50fa7b");
                    document.getElementById("runState").textContent = pinning
                      ? `Completed with pinning enabled (${pinDelayMs}ms).`
                      : "Completed.";
                  }

                  document.getElementById("runBtn").addEventListener("click", () => runComparison().catch(err => {
                    console.error(err);
                    document.getElementById("runState").textContent = "Failed to run comparison.";
                  }));
                </script>
                </body>
                </html>
                """.formatted(safeMsg);
    }
}
