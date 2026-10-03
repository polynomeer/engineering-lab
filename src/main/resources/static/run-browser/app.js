'use strict';
const $ = id => document.getElementById(id);
const query = new URLSearchParams(location.search);
const content = $('content');
let pageSize = 20;
let artifactDownloadMaxBytes = 0;
const format = value => value == null ? 'Not recorded' : typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
function node(tag, text, cls) { const e = document.createElement(tag); if (text != null) e.textContent = text; if (cls) e.className = cls; return e; }
function link(text, href, cls) { const e = node('a', text, cls); e.href = href; return e; }
function route(params) { return '/runs/ui?' + new URLSearchParams(params); }
function button(text, action) { const e = node('button', text); e.type = 'button'; e.onclick = () => work(action, e); return e; }
async function api(path) { const response = await fetch(path, {signal: AbortSignal.timeout(15000)}); if (!response.ok) throw new Error(`Request failed (${response.status}). Check the Run ID or retry.`); return response.json(); }
async function work(action, control) {
  $('error').hidden = true; $('status').textContent = 'Loading evidence…'; if (control) control.disabled = true;
  try { await action(); $('status').textContent = 'Evidence loaded from the run store.'; }
  catch (error) { $('error').textContent = error.message; $('error').hidden = false; $('status').textContent = 'Evidence could not be loaded.'; }
  finally { if (control) control.disabled = false; }
}
function table(title, headers, rows, changed = []) {
  const wrap = node('div', null, 'table-wrap'); wrap.tabIndex = 0; wrap.setAttribute('aria-label', title);
  const t = node('table'); t.append(node('caption', title)); const head = node('thead'), hr = node('tr');
  headers.forEach(h => { const th = node('th', h); th.scope = 'col'; hr.append(th); }); head.append(hr); t.append(head);
  const body = node('tbody');
  rows.forEach((row, index) => { const tr = node('tr'); if (changed[index]) tr.className = 'different'; row.forEach((value, i) => { const cell = node(i === 0 ? 'th' : 'td'); if (i === 0) cell.scope = 'row'; cell.append(value instanceof Node ? value : node('span', format(value))); tr.append(cell); }); body.append(tr); });
  t.append(body); wrap.append(t); return wrap;
}
function raw(title, value) { const details = node('details'); details.append(node('summary', title), node('pre', format(value))); return details; }
function actions(run) {
  const wrap = node('div', null, 'actions');
  wrap.append(button('Use as baseline', () => { selectRun('baseline', run.runId); $('candidate').focus(); }), button('Use as candidate', () => { selectRun('candidate', run.runId); $('baseline').focus(); }));
  if (!['QUEUED','RUNNING'].includes(run.status)) wrap.append(link('Export evidence (NDJSON)', `/runs/${encodeURIComponent(run.runId)}/export`, 'action'));
  return wrap;
}
function artifactAction(run, artifact) {
  const uuid = '[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}';
  const jfr = new RegExp('^jfr_' + uuid + '$').test(artifact.artifactId);
  const log = new RegExp('^log_' + uuid + '$').test(artifact.artifactId);
  const managed = (jfr && artifact.relativePath === `jfr/${artifact.artifactId}.jfr` && artifact.mediaType === 'application/octet-stream')
    || (log && artifact.relativePath === `logs/${artifact.artifactId}.ndjson` && artifact.mediaType === 'application/x-ndjson');
  if (!managed) return node('span', 'Reference only · obtain the file from its owner.', 'artifact-note');
  if (['QUEUED','RUNNING'].includes(run.status)) return node('span', 'Available after this run ends. Refresh to check.', 'artifact-note');
  if (artifact.byteSize > artifactDownloadMaxBytes) return node('span', 'Exceeds this server’s download size limit.', 'artifact-note');
  const a = link(jfr ? 'Download JFR' : 'Download log',
    `/runs/${encodeURIComponent(run.runId)}/artifacts/${encodeURIComponent(artifact.artifactId)}/download`, 'action');
  a.target = '_blank'; a.rel = 'noopener';
  a.setAttribute('aria-label', `${a.textContent}: ${artifact.relativePath} (opens a new tab)`);
  a.setAttribute('aria-describedby', 'artifact-download-help');
  return a;
}
async function catalog() {
  const items = await api('/experiments'); $('catalog').replaceChildren(link('All runs', '/runs/ui'));
  items.forEach(item => { const a = link(item.title, route({experiment: item.id})); a.append(node('small', item.category)); if (query.get('experiment') === item.id) a.setAttribute('aria-current','page'); $('catalog').append(a); });
}
async function runs() {
  const experiment = query.get('experiment'); const before = query.get('before');
  const params = new URLSearchParams({limit:String(pageSize)}); if (experiment) params.set('experimentType',experiment); if (before) params.set('before', before);
  if (experiment) {
    const descriptor = await api('/experiments/' + encodeURIComponent(experiment));
    content.append(node('h2', descriptor.title), node('p', `${descriptor.category} · Input: ${descriptor.input} · Measurement: ${descriptor.measurementModes.join(', ')}`), raw('Supported parameters', descriptor.parameters));
  } else content.append(node('h2','Recent runs'));
  const page = await api('/runs?' + params);
  if (!page.runs.length) content.append(node('p', 'No runs on this page. Launch an experiment or submit a scenario with the CLI.'));
  else content.append(table('Stored runs · newest first', ['Run / scenario', 'Experiment', 'Status', 'Created'], page.runs.map(run => {
    const label = node('div'); label.append(link(run.scenarioId, route({run:run.runId})), node('small', run.runId));
    return [label, run.experimentType, run.status, new Date(run.createdAtEpochMs).toLocaleString()];
  })));
  const paging = node('div',null,'actions'); paging.append(link('Newest runs', route(experiment ? {experiment} : {}), 'action'));
  if (page.nextBefore) paging.append(link('Older runs', route({...experiment && {experiment}, before:page.nextBefore}), 'action')); content.append(paging);
}
async function detail(id) {
  const encoded = encodeURIComponent(id);
  const [run, artifacts] = await Promise.all([api('/runs/'+encoded), api(`/runs/${encoded}/artifacts`)]);
  content.append(node('h2',run.scenarioId), node('p',run.runId), node('p',`${run.experimentType} · ${run.status}`, 'badge'), actions(run));
  if (run.failureMessage) content.append(node('p',run.failureMessage,'warn'));
  if (['QUEUED','RUNNING'].includes(run.status)) content.append(node('p','This run is active. Refresh for updated evidence; export is available after completion.','warn'));
  content.append(button('Refresh run', () => { location.reload(); }));
  content.append(node('h3','Hypothesis'), node('p',run.runMetadata.parameters['scenario.hypothesis'] || 'No hypothesis recorded.'));
  content.append(node('h3','Recorded conditions'), table('Environment and provenance', ['Field','Value'], [...Object.entries(run.runMetadata.environment), ...Object.entries(run.runMetadata.git).map(([key,value]) => ['git.'+key,value])]), raw('Parameters and canonical scenario', run.runMetadata.parameters));
  content.append(node('h3','Summary'), table('Recorded summary · original metric names', ['Metric / kind', 'Value'], [...Object.entries(run.summary.counters).map(([k,v])=>[k+' / counter',v]), ...Object.entries(run.summary.gauges).map(([k,v])=>[k+' / gauge',v])]), raw('Summary details', run.summary.details));
  content.append(node('h3','Artifacts'));
  const help = node('p', 'JFR and run logs are checked for size and checksum before download. Opens a new tab; if a file is missing, changed, or the server is busy, the error appears there.');
  help.id = 'artifact-download-help'; content.append(help);
  if (!artifacts.length) content.append(node('p','No artifact references recorded.'));
  else content.append(table('Referenced evidence', ['Path','Media type / bytes','SHA-256','Download'], artifacts.map(a => [a.relativePath, `${a.mediaType} / ${a.byteSize}`, a.sha256, artifactAction(run, a)])));
  content.append(node('h3','Metric observations'), node('p','One page at a time. Export a completed Run to retain the full timeline.'));
  const metrics = node('div'); content.append(metrics);
  async function page(after = -1) {
    const data = await api(`/runs/${encoded}/metrics?limit=${pageSize}&after=${after}`);
    metrics.replaceChildren();
    if (!data.metrics.length) metrics.append(node('p','No further metric observations.'));
    else metrics.append(table('Raw metric page · original counters, gauges and labels', ['Sequence / time','Observation'], data.metrics.map(m => [m.sequence+' / '+new Date(m.point.timestampEpochMs).toISOString(), node('pre',format(m.point))])));
    const controls = node('div', null, 'actions'); controls.append(button('First metric page', () => page()));
    if (data.nextAfter != null) controls.append(button('Next metric page', () => page(data.nextAfter))); metrics.append(controls);
  }
  await page();
}
async function compare(baseline, candidate) {
  const result = await api('/runs/compare?' + new URLSearchParams({baseline,candidate}));
  content.append(node('h2','Compare runs'));
  const refs = node('div',null,'actions'); refs.append(link('Baseline · '+result.baseline.scenarioId, route({run:baseline})), link('Candidate · '+result.candidate.scenarioId, route({run:candidate}))); content.append(refs);
  const warnings = node('ul',null,'warn'); result.cautions.forEach(text => warnings.append(node('li',text))); content.append(warnings);
  content.append(table('Run state', ['Field','Baseline','Candidate'], [['Run ID',baseline,candidate],['Status',result.baseline.status,result.candidate.status],['Failure',result.baseline.failureMessage,result.candidate.failureMessage]]));
  content.append(node('h3','Recorded conditions'), table('Changed conditions are marked explicitly', ['Field','Baseline','Candidate','Match'], result.conditions.map(c => [c.field,c.baseline,c.candidate,c.different?'Different':'Same']), result.conditions.map(c=>c.different)));
  content.append(node('h3','Measurements'), table('Summary values · missing values are not zero', ['Metric / kind','Unit','Baseline','Candidate'], result.values.map(v => [v.name+' / '+v.kind,v.unit,v.baseline,v.candidate])));
  content.append(node('p','No automatic winner or percentage improvement is calculated. Keep input, environment, units and repeated measurements in your interpretation.'));
  for (const run of [result.baseline,result.candidate]) { content.append(node('h3',run.runId), actions(run)); }
  content.append(node('h3','Write a finding'), node('p','Use docs/findings/TEMPLATE.md. Record these Run IDs, the comparison URL, export checksums, observations, interpretation and limitations separately.'));
}
function selectRun(role, id) { $(role).value = id; try { sessionStorage.setItem('lab.compare.'+role, id); } catch (_) { /* Selection still works when storage is disabled. */ } }
for (const role of ['baseline','candidate']) { let saved = ''; try { saved = sessionStorage.getItem('lab.compare.'+role) || ''; } catch (_) {} selectRun(role, query.get(role) ?? saved); $(role).onchange = () => selectRun(role, $(role).value.trim()); }
$('comparison-form').onsubmit = event => { event.preventDefault(); location.href = route({baseline:$('baseline').value.trim(),candidate:$('candidate').value.trim()}); };
work(async () => { const options = await api('/runs/ui/options'); pageSize = options.pageSize; artifactDownloadMaxBytes = options.artifactDownloadMaxBytes; await catalog(); if (query.has('run')) await detail(query.get('run')); else if (query.has('baseline') && query.has('candidate')) await compare(query.get('baseline'),query.get('candidate')); else await runs(); });
