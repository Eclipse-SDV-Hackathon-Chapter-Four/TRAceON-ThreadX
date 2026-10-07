/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 Contributors to the Eclipse Foundation
 * SPDX-License-Identifier: MIT
 * Portions of this file were generated with AI assistance.
 */
/*
 * Serves a self-contained live dashboard at "/" (and "/dashboard"). The page
 * uses the browser EventSource API against this same server's SSE endpoints
 * (/telemetry/entries and /logs/entries) — so it is same-origin and needs no
 * CORS. Pure HTML/JS, no build step, no external assets.
 */
package org.traceon;

import java.io.IOException;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class DashboardServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(200);
        resp.setContentType("text/html; charset=utf-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.getWriter().write(HTML);
    }

    private static final String HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>TRAceON — live dashboard</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; font:14px/1.4 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;
         background:#0d1117; color:#e6edf3; }
  header { padding:12px 18px; background:#161b22; border-bottom:1px solid #30363d;
           display:flex; align-items:center; gap:14px; }
  header h1 { font-size:16px; margin:0; letter-spacing:.5px; }
  #status { font-size:12px; padding:2px 8px; border-radius:10px; }
  .up { background:#1a7f37; color:#fff; } .down { background:#8b1a1a; color:#fff; }
  main { display:grid; grid-template-columns: 320px 1fr; gap:14px; padding:14px; }
  .card { background:#161b22; border:1px solid #30363d; border-radius:8px; padding:14px; }
  .card h2 { margin:0 0 10px; font-size:13px; text-transform:uppercase; letter-spacing:1px; color:#7d8590; }
  .metric { display:flex; justify-content:space-between; padding:6px 0; border-bottom:1px solid #21262d; }
  .metric .v { font-weight:600; }
  .vec-label { padding:6px 0 2px; color:#e6edf3; }
  .metric.sub { padding:2px 0 2px 16px; border-bottom:none; color:#9aa5b1; }
  .metric.sub span:first-child { color:#7d8590; }
  #logs { height:70vh; overflow-y:auto; }
  .log { padding:4px 8px; border-radius:4px; margin-bottom:3px; white-space:pre-wrap;
         border-left:3px solid #30363d; }
  .log .ctx { color:#7d8590; } .log .ts { color:#56606a; font-size:11px; }
  .DLT_FATAL  { background:#3d1418; border-left-color:#f85149; }
  .DLT_ERROR  { background:#36140f; border-left-color:#f85149; }
  .DLT_WARN   { background:#3b2d10; border-left-color:#d29922; }
  .DLT_INFO   { border-left-color:#2f81f7; }
  .DLT_DEBUG  { color:#9aa5b1; border-left-color:#484f58; }
  .DLT_VERBOSE{ color:#6e7681; border-left-color:#30363d; }
  .sev { font-weight:700; }
</style>
</head>
<body>
<header>
  <h1>TRAceON</h1>
  <span id="status" class="down">connecting…</span>
  <span style="font-size:12px;color:#7d8590">live from the Java server (SSE)</span>
</header>
<main>
  <section class="card">
    <h2>Telemetry</h2>
    <div id="telemetry"><div class="metric"><span>waiting…</span><span class="v"></span></div></div>
  </section>
  <section class="card">
    <h2>Logs (ISO 17978-3)</h2>
    <div id="logs"></div>
  </section>
</main>
<script>
  const F = ["pressure_hPa","temperature_degC","humidity_perc","acceleration_mg","magnetic_mG"];
  const tEl = document.getElementById("telemetry");
  const lEl = document.getElementById("logs");
  const sEl = document.getElementById("status");

  const tel = new EventSource("/telemetry/entries");
  tel.onopen = () => { sEl.textContent="connected"; sEl.className="up"; };
  tel.onerror = () => { sEl.textContent="disconnected"; sEl.className="down"; };
  tel.onmessage = (e) => {
    let d; try { d = JSON.parse(e.data); } catch { return; }
    tEl.innerHTML = F.filter(k=>k in d).map(k => {
      const v = d[k];
      if (Array.isArray(v)) {
        // Vector: field name on its own line, then X/Y/Z each on their own row.
        const axes = ["X","Y","Z"];
        const rows = v.map((n,i) =>
          `<div class="metric sub"><span>${axes[i]||i}</span>`
          + `<span class="v">${(+n).toFixed(1)}</span></div>`).join("");
        return `<div class="vec-label">${k}</div>${rows}`;
      }
      return `<div class="metric"><span>${k}</span>`
           + `<span class="v">${typeof v==="number"? v.toFixed(2): v}</span></div>`;
    }).join("")
      || '<div class="metric"><span>(no fields yet)</span><span class="v"></span></div>';
  };

  const log = new EventSource("/logs/entries");
  log.onmessage = (e) => {
    let env; try { env = JSON.parse(e.data); } catch { return; }
    const p = env.payload || env;              // EventEnvelope -> LogEntry
    const sev = (p.severity||"DLT_INFO");
    const ctx = (p.context && p.context.context_id) || p.context || "";
    const row = document.createElement("div");
    row.className = "log " + sev;
    row.innerHTML = `<span class="sev">${sev.replace('DLT_','')}</span> `
      + `<span class="ctx">[${ctx}]</span> ${(p.msg||"")} `
      + `<span class="ts">${p.timestamp||""}</span>`;
    lEl.prepend(row);
    while (lEl.childNodes.length > 200) lEl.removeChild(lEl.lastChild);
  };
</script>
</body>
</html>
""";
}
