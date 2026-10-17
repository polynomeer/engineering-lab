import { PatternExplorer } from './PatternExplorer';
import { MetricCharts } from './RunMetricCharts';
import {
  CubeIcon,
  PlayIcon,
  ReaderIcon,
  BarChartIcon,
  GearIcon,
  ArchiveIcon,
  LightningBoltIcon,
} from '@radix-ui/react-icons';
import { WorkerEvidence, type Execution } from './WorkerEvidence';
import { RecentRuns } from './RecentRuns';
import { capabilities as parseCapabilities } from './capabilities';
import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  ApiError,
  get,
  optional,
  apiUrl,
  terminal,
  artifactReason,
  parseRoute,
  type Run,
  type Descriptor,
  type Module,
  type MetricPage,
  type Artifact,
  type Comparison,
} from './api';
import './style.css';
import { SettingsPage } from './SettingsPage';
import { loadSettings, saveSettings } from './settings';
import { moduleViews } from './moduleViews';
import { DatabaseTool, IoEchoTool } from './LegacyTools';
import { ExecutionPage } from './ExecutionForm';
import { comparisonCsv, download } from './execution';
import { Table, Raw, Badge, Actions, format } from './components';
const route = parseRoute(location.pathname, location.search);
try {
  const saved = loadSettings();
  if (route.kind === 'compare') {
    for (const key of ['baseline', 'candidate'] as const)
      if (!route.query.has(key) && saved[key]) route.query.set(key, saved[key]);
    saveSettings({
      ...saved,
      baseline: route.query.get('baseline') ?? '',
      candidate: route.query.get('candidate') ?? '',
    });
  }
  if (route.kind === 'list' && route.query.has('experiment'))
    saveSettings({ ...saved, filter: route.query.get('experiment') ?? '' });
  if (
    route.kind === 'list' &&
    !route.query.has('experiment') &&
    saved.filter &&
    saved.filter !== 'all'
  )
    route.query.set('experiment', saved.filter);
} catch {
  /* Navigation remains available without browser storage. */
}
const runLink = (id: string) => '/app/runs/' + encodeURIComponent(id);
function App() {
  const [modules, setModules] = useState<Module[]>([]),
    [catalog, setCatalog] = useState<Descriptor[]>([]);
  const [features, setFeatures] = useState<Record<string, boolean>>({});
  const [bootstrapError, setBootstrapError] = useState('');
  const [error, setError] = useState(''),
    [loading, setLoading] = useState(true),
    [legacy, setLegacy] = useState(false);
  const [options, setOptions] = useState({
    pageSize: 20,
    artifactDownloadMaxBytes: 0,
  });
  const [page, setPage] = useState<{ runs: Run[]; nextBefore: number | null }>({
    runs: [],
    nextBefore: null,
  });
  const [run, setRun] = useState<Run | null>(null),
    [artifacts, setArtifacts] = useState<Artifact[]>([]);
  const [metrics, setMetrics] = useState<MetricPage>({
      metrics: [],
      nextAfter: null,
    }),
    [after, setAfter] = useState(-1);
  const [comparison, setComparison] = useState<Comparison | null>(null),
    [refresh, setRefresh] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      get<Descriptor[]>('/experiments', controller.signal),
      optional<Module[]>('/modules', controller.signal),
      optional<unknown>('/dashboard/v1/capabilities', controller.signal),
      get<typeof options>('/runs/ui/options', controller.signal),
    ])
      .then(([items, groups, capabilities, limits]) => {
        setCatalog(items);
        setModules(groups ?? []);
        const supported = parseCapabilities(capabilities);
        setLegacy(supported === null);
        setFeatures(supported?.features ?? {});
        setOptions(limits);
        setBootstrapError('');
      })
      .catch((e) => {
        if (!controller.signal.aborted) setBootstrapError(String(e.message));
      });
    return () => controller.abort();
  }, [refresh]);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let delay = 2000;
    async function load() {
      try {
        setLoading(true);
        if (route.kind === 'list') {
          const params = new URLSearchParams({
            limit: String(Math.min(options.pageSize, 100)),
          });
          for (const key of ['experiment', 'before'])
            if (route.query.get(key))
              params.set(key === 'experiment' ? 'experimentType' : key, route.query.get(key)!);
          setPage(await get('/runs?' + params, controller.signal));
        } else if (route.kind === 'detail') {
          const id = encodeURIComponent(route.id);
          const [detail, refs, observations] = await Promise.all([
            get<Run>('/runs/' + id, controller.signal),
            get<Artifact[]>(`/runs/${id}/artifacts`, controller.signal),
            get<MetricPage>(
              `/runs/${id}/metrics?after=${after}&limit=${Math.min(options.pageSize, 100)}`,
              controller.signal,
            ),
          ]);
          setRun(detail);
          setArtifacts(refs);
          setMetrics(observations);
          delay = 2000;
          if (!terminal(detail) && !document.hidden) timer = setTimeout(load, delay);
        } else if (
          route.kind === 'compare' &&
          route.query.get('baseline') &&
          route.query.get('candidate')
        ) {
          setComparison(
            await get(
              '/runs/compare?' +
                new URLSearchParams({
                  baseline: route.query.get('baseline')!,
                  candidate: route.query.get('candidate')!,
                }),
              controller.signal,
            ),
          );
        }
        setError('');
      } catch (e) {
        if (!controller.signal.aborted) {
          setError(e instanceof Error ? e.message : String(e));
          if (
            route.kind === 'detail' &&
            !document.hidden &&
            (!(e instanceof ApiError) || e.status >= 500 || e.status === 429)
          ) {
            delay = Math.min(delay * 2, 15000);
            timer = setTimeout(load, delay);
          }
        }
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    }
    const hidden = () => {
      if (document.hidden) clearTimeout(timer);
    };
    document.addEventListener('visibilitychange', hidden);
    void load();
    return () => {
      controller.abort();
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', hidden);
    };
  }, [refresh, after, options.pageSize]);
  useEffect(() => {
    const visible = () => {
      if (!document.hidden) setRefresh((n) => n + 1);
    };
    document.addEventListener('visibilitychange', visible);
    return () => document.removeEventListener('visibilitychange', visible);
  }, []);
  const [pageStatus, setPageStatus] = useState('all');
  const [selectedView, setDetailView] = useState('auto');
  const [execution, setExecution] = useState<Execution | null>(null);
  const detailView =
    selectedView === 'auto'
      ? execution?.execution?.definition.measurementScope === 'CHECK_RESULTS'
        ? 'checks'
        : 'overview'
      : selectedView;
  useEffect(() => {
    const desktop = window.matchMedia('(min-width: 1024px)');
    const restoreDesktopNavigation = () => {
      const drawer = document.querySelector<HTMLDetailsElement>('.navigation-drawer');
      if (desktop.matches && drawer) drawer.open = true;
    };
    desktop.addEventListener('change', restoreDesktopNavigation);
    return () => desktop.removeEventListener('change', restoreDesktopNavigation);
  }, []);
  const DetailPanel = run && moduleViews[run.experimentType]?.detail;
  const metricContent = run && (
    <>
      <section className="detail-panel" hidden={!['overview', 'metrics'].includes(detailView)}>
        <MetricCharts run={run} page={metrics} pageSize={options.pageSize} />
        <details className="metric-source" open={detailView === 'overview'}>
          <summary id="run-metrics">원본 메트릭 요약</summary>
          {Object.keys(run.summary.counters).length + Object.keys(run.summary.gauges).length >
            0 && (
            <Table
              label="원본 metric 이름과 값"
              headers={['측정값', '종류', '값']}
              rows={[
                ...Object.entries(run.summary.counters).map(([k, v]) => [k, 'counter', format(v)]),
                ...Object.entries(run.summary.gauges).map(([k, v]) => [k, 'gauge', format(v)]),
              ]}
            />
          )}
          <Raw label="결과 상세" value={run.summary.details} />
        </details>
      </section>
      <section className="detail-panel" hidden={!['overview', 'metrics'].includes(detailView)}>
        <h2>관측 기록</h2>
        {metrics.metrics.length === 0 && after === -1 && metrics.nextAfter === null ? (
          <p>
            이 페이지에 측정값이 없습니다. 검증 실험은 위의 검증 결과와 실행·검증 내보내기를
            확인하세요.
          </p>
        ) : (
          <>
            <details className="metric-source" open={detailView === 'overview'}>
              <summary>원본 관측값</summary>
              <Table
                label="원본 관측 · 한 페이지씩 조회"
                headers={['순서 / 시각', '관측값']}
                rows={metrics.metrics.map((m) => [
                  <>
                    {m.sequence}
                    <small>{new Date(m.point.timestampEpochMs).toLocaleString()}</small>
                  </>,
                  <pre>{format(m.point)}</pre>,
                ])}
              />
            </details>
            <div className="actions">
              <button onClick={() => setAfter(-1)} disabled={loading}>
                첫 관측
              </button>
              {metrics.nextAfter !== null && (
                <button disabled={loading} onClick={() => setAfter(metrics.nextAfter!)}>
                  다음 관측
                </button>
              )}
            </div>
          </>
        )}
      </section>
    </>
  );
  const groups = modules.length
    ? modules
    : [
        {
          module: {
            id: 'all',
            title: '등록된 실험',
            collection: 'engineering',
          },
          experiments: catalog,
        },
      ];
  return (
    <>
      <a className="skip" href="#content">
        본문으로 이동
      </a>
      <div className="layout">
        <aside className="sidebar">
          <a className="sidebar-brand" href="/app/runs">
            <CubeIcon width={24} height={24} aria-hidden="true" />
            Engineering Lab<small>EXPERIMENT OBSERVATORY</small>
          </a>
          <details
            className="navigation-drawer"
            open
            onKeyDown={(event) => {
              if (event.key === 'Escape' && window.innerWidth < 1024) {
                event.currentTarget.open = false;
                event.currentTarget.querySelector('summary')?.focus();
              }
            }}
          >
            <summary>메뉴</summary>
            <nav aria-label="대시보드 탐색">
              <a href="/app/catalog" aria-current={route.kind === 'catalog' ? 'page' : undefined}>
                <CubeIcon width={18} height={18} aria-hidden="true" />패턴 탐색
              </a>
              <a
                href="/app/experiments"
                aria-current={route.kind === 'execute' ? 'page' : undefined}
              >
                <PlayIcon width={18} height={18} aria-hidden="true" />
                실험 실행
              </a>
              <a
                href="/app/runs"
                aria-current={['list', 'detail'].includes(route.kind) ? 'page' : undefined}
              >
                <ReaderIcon width={18} height={18} aria-hidden="true" />
                실행 기록
              </a>
              <a href="/app/compare" aria-current={route.kind === 'compare' ? 'page' : undefined}>
                <BarChartIcon width={18} height={18} aria-hidden="true" />
                Run 비교
              </a>
              <a href="/app/tools/ingestion-db">
                <ArchiveIcon width={18} height={18} aria-hidden="true" />
                현재 DB 조회
              </a>
              <a href="/app/tools/io-echo">
                <LightningBoltIcon width={18} height={18} aria-hidden="true" />
                IO 단건 데모
              </a>
              <a href="/app/settings">
                <GearIcon width={18} height={18} aria-hidden="true" />
                설정 이전
              </a>
            </nav>
            <p className="eyebrow">EXPERIMENT CATALOG</p>
            <a href="/app/runs?experiment=">모든 실행</a>
            {groups.map((group) => (
              <section className="catalog-group" key={group.module.id}>
                <h2>{group.module.title}</h2>

                {group.experiments.map((item) => (
                  <a
                    key={item.id}
                    aria-current={
                      (route.query.get('experiment') ?? run?.experimentType) === item.id
                        ? 'page'
                        : undefined
                    }
                    href={'/app/experiments?experiment=' + encodeURIComponent(item.id)}
                  >
                    {item.title}
                  </a>
                ))}
              </section>
            ))}

            <a
              href={
                new URL('/ui?legacy=true', import.meta.env.VITE_LEGACY_BASE ?? location.origin).href
              }
            >
              기존 실험 실행 ↗
            </a>
            <p className="note">서버에 등록된 모듈만 표시됩니다.</p>
          </details>
          <p className="connection-status">
            {bootstrapError ? 'API 연결 확인 필요' : loading ? 'API 확인 중' : 'API 조회 완료'}
            <small>등록된 기능과 기록된 환경을 표시합니다.</small>
          </p>
        </aside>
        <main id="content">
          <div className="page-heading">
            <div>
              <p className="eyebrow breadcrumb">
                Engineering Lab <span>/</span>{' '}
                {catalog.find((item) => item.id === run?.experimentType)?.title ??
                  run?.experimentType ??
                  'Workspace'}
              </p>
              <h1>
                {route.kind === 'catalog' ? '패턴 탐색' : route.kind === 'settings'
                  ? '설정 이전'
                  : route.kind === 'database'
                    ? '현재 DB 조회'
                    : route.kind === 'echo'
                      ? 'IO 단건 데모'
                      : route.kind === 'execute'
                        ? '실험 실행'
                        : route.kind === 'detail'
                          ? '실행 기록과 검증 증거'
                          : route.kind === 'compare'
                            ? '실행 비교'
                            : route.kind === 'missing'
                              ? '페이지를 찾을 수 없습니다'
                              : '실행 기록'}
              </h1>
            </div>
            <div className="heading-actions">
              {run && (
                <a
                  className="secondary-action"
                  href={'/app/compare?baseline=' + encodeURIComponent(run.runId)}
                >
                  Run 비교
                </a>
              )}
              <a className="primary-action" href="/app/experiments">
                새 실험 실행
              </a>
              <button onClick={() => setRefresh((n) => n + 1)} disabled={loading}>
                새로고침
              </button>
            </div>
          </div>
          {legacy && <p className="note">기존 API 호환 모드 · 공통 실행·조회 API를 사용합니다.</p>}
          <p role="status" className="status">
            {loading
              ? '불러오는 중…'
              : route.kind === 'settings'
                ? '이 브라우저에 저장된 설정 관리'
                : route.kind === 'database' || route.kind === 'echo'
                  ? '현재 서버의 도구 · Run 증거와 별도'
                  : route.kind === 'execute' || route.kind === 'catalog'
                    ? '서버에 등록된 실험과 입력 범위'
                    : '저장소에서 조회한 실행 증거'}
          </p>
          {(error || bootstrapError) && (
            <div role="alert" className="error">
              {error || bootstrapError}{' '}
              <button onClick={() => setRefresh((n) => n + 1)}>다시 시도</button>
            </div>
          )}
          {route.kind === 'catalog' && <PatternExplorer catalog={catalog} modules={modules} query={route.query} refresh={refresh} />}
          {route.kind === 'settings' && <SettingsPage catalog={catalog} />}
          {route.kind === 'database' && <DatabaseTool />}
          {route.kind === 'echo' && <IoEchoTool />}
          {route.kind === 'execute' && (
            <ExecutionPage
              catalog={catalog}
              experiment={route.query.get('experiment')}
              checkReadiness={features.localJvm === true || features.container === true}
            />
          )}
          {route.kind === 'missing' && <a href="/app/runs">실행 기록으로 돌아가기</a>}
          {route.kind === 'list' && (
            <>
              <div className="history-toolbar">
                <form action="/app/runs">
                  <label>
                    실험 필터
                    <select name="experiment" defaultValue={route.query.get('experiment') ?? ''}>
                      <option value="">전체 실험</option>
                      {catalog.map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.title}
                        </option>
                      ))}
                    </select>
                  </label>
                  <button type="submit">기록 조회</button>
                </form>
                <label>
                  현재 페이지 상태
                  <select value={pageStatus} onChange={(e) => setPageStatus(e.target.value)}>
                    <option value="all">전체 상태</option>
                    <option value="RUNNING">실행 중</option>
                    <option value="SUCCEEDED">성공</option>
                    <option value="FAILED">실패</option>
                  </select>
                </label>
              </div>
              <p className="note">입력과 환경을 확인하고, 같은 조건의 실행을 비교하세요.</p>
              <Table
                label="저장된 실행 · 최신순"
                headers={['시나리오 / Run ID', '실험', '상태', '생성 시각']}
                rows={page.runs
                  .filter((r) => pageStatus === 'all' || r.status === pageStatus)
                  .map((r) => [
                    <>
                      <a href={runLink(r.runId)}>{r.scenarioId}</a>
                      <small>{r.runId}</small>
                    </>,
                    r.experimentType,
                    <Badge run={r} />,
                    new Date(r.createdAtEpochMs).toLocaleString(),
                  ])}
              />
              {!loading &&
                !error &&
                pageStatus !== 'all' &&
                page.runs.length > 0 &&
                !page.runs.some((item) => item.status === pageStatus) && (
                  <p>현재 페이지에 선택한 상태의 실행이 없습니다.</p>
                )}
              {!loading && !error && !page.runs.length && (
                <p>
                  이 페이지에 저장된 실행이 없습니다. 실험 실행 화면이나 CLI에서 실험을 실행하세요.
                </p>
              )}
              <div className="actions">
                <a
                  href={
                    '/app/runs' +
                    (route.query.get('experiment')
                      ? '?experiment=' + encodeURIComponent(route.query.get('experiment')!)
                      : '')
                  }
                >
                  최신 실행
                </a>
                {page.nextBefore !== null && (
                  <a
                    href={
                      '/app/runs?' +
                      new URLSearchParams({
                        ...Object.fromEntries(route.query),
                        before: String(page.nextBefore),
                      })
                    }
                  >
                    이전 실행
                  </a>
                )}
              </div>
            </>
          )}
          {route.kind === 'detail' && run && (
            <>
              <section className="run-strip" aria-label="선택한 실행">
                <Badge run={run} />
                <h2>{run.scenarioId}</h2>
                <span className="mono" title={run.runId}>
                  {run.runId}
                </span>
                {run.completedAtEpochMs && run.startedAtEpochMs ? (
                  <span className="duration">
                    {((run.completedAtEpochMs - run.startedAtEpochMs) / 1000).toFixed(2)} s
                  </span>
                ) : null}
                {execution?.execution && (
                  <span className="badge">
                    {execution.execution.evidenceComplete ? '증거 완료' : '부분 증거'}
                  </span>
                )}
              </section>
              <div className="result-tabs" aria-label="결과 보기">
                {[
                  ['overview', '전체'],
                  ['checks', '검증 결과'],
                  ['metrics', '메트릭'],
                  ['environment', '실행 환경'],
                  ['artifacts', '아티팩트'],
                ].map(([id, title]) => (
                  <button
                    key={id}
                    disabled={id === 'checks' && !features.checksRead}
                    aria-pressed={detailView === id}
                    onClick={() => setDetailView(id)}
                  >
                    {title}
                  </button>
                ))}
              </div>
              <div className="run-actions" hidden={!['overview', 'artifacts'].includes(detailView)}>
                <Actions run={run} />
              </div>
              {run.failureMessage && <p className="error">{run.failureMessage}</p>}
              <section className="hypothesis-panel" hidden={!['environment'].includes(detailView)}>
                <h2>가설</h2>
                <p>
                  {run.runMetadata.parameters['scenario.hypothesis'] ?? '기록된 가설이 없습니다.'}
                </p>
              </section>
              <section hidden={detailView !== 'overview'}>
                {DetailPanel && <DetailPanel id={run.runId} status={run.status} />}
              </section>
              {(features.executionEvidence || features.checksRead) && (
                <WorkerEvidence
                  view={detailView}
                  onExecution={setExecution}
                  metrics={metricContent}
                  key={run.runId}
                  run={run}
                  features={features}
                  pageSize={options.pageSize}
                />
              )}
              {(!(features.executionEvidence || features.checksRead) ||
                (detailView === 'overview' &&
                  execution?.execution?.definition.kind !== 'IN_PROCESS')) &&
                metricContent}
              <RecentRuns run={run} />
              <section
                className="detail-panel"
                hidden={!['overview', 'environment'].includes(detailView)}
              >
                <h2 id="run-environment">입력과 실행 환경</h2>
                <Raw label="파라미터와 시나리오 정의" value={run.runMetadata.parameters} />
                <Raw
                  label="서버 JVM / 운영체제 / Git"
                  value={{
                    ...run.runMetadata.environment,
                    git: run.runMetadata.git,
                  }}
                />
              </section>
              <section
                className="detail-panel"
                hidden={!['overview', 'artifacts'].includes(detailView)}
              >
                <h2 id="run-artifacts">첨부 증거</h2>
                <p className="note">
                  다운로드 시 서버가 크기와 SHA-256을 검증합니다. 오류는 새 탭에 표시됩니다.
                </p>
                {!artifacts.length ? (
                  <p>등록된 첨부 증거가 없습니다.</p>
                ) : (
                  <Table
                    label="첨부 파일과 무결성"
                    headers={['경로', '크기 / SHA-256', '다운로드']}
                    rows={artifacts.map((a) => [
                      a.relativePath,
                      <>
                        {a.byteSize} bytes<small>{a.sha256}</small>
                      </>,
                      artifactReason(
                        run,
                        a,
                        options.artifactDownloadMaxBytes,
                        features.workerReportDownload,
                      ) ?? (
                        <a
                          target="_blank"
                          rel="noopener"
                          href={apiUrl(
                            `/runs/${encodeURIComponent(run.runId)}/artifacts/${encodeURIComponent(a.artifactId)}/download`,
                          )}
                        >
                          {a.artifactId.startsWith('worker_')
                            ? 'REPORT 다운로드 ↗'
                            : '다운로드 ↗'}
                        </a>
                      ),
                    ])}
                  />
                )}
              </section>
            </>
          )}
          {route.kind === 'compare' && (
            <>
              <form action="/app/compare">
                <label>
                  기준 Run ID
                  <input
                    name="baseline"
                    required
                    defaultValue={route.query.get('baseline') ?? ''}
                  />
                </label>
                <label>
                  비교 Run ID
                  <input
                    name="candidate"
                    required
                    defaultValue={route.query.get('candidate') ?? ''}
                  />
                </label>
                <button type="submit">비교하기</button>
              </form>
              {comparison && (
                <>
                  <ul className="warning">
                    {comparison.cautions.map((c) => (
                      <li key={c}>{c}</li>
                    ))}
                  </ul>
                  <div className="actions">
                    <button
                      onClick={() =>
                        download(
                          'run-comparison.csv',
                          comparisonCsv(comparison),
                          'text/csv;charset=utf-8',
                        )
                      }
                    >
                      비교 요약 CSV
                    </button>
                    <button
                      onClick={() =>
                        download(
                          'run-comparison.json',
                          JSON.stringify(comparison, null, 2),
                          'application/json',
                        )
                      }
                    >
                      비교 JSON
                    </button>
                    <a href={runLink(comparison.baseline.runId)}>기준 실행 상세</a>
                    <a href={runLink(comparison.candidate.runId)}>비교 실행 상세</a>
                  </div>
                  <Table
                    label="실행 상태"
                    headers={['항목', '기준', '비교']}
                    rows={[
                      ['상태', comparison.baseline.status, comparison.candidate.status],
                      [
                        '실패 사유',
                        format(comparison.baseline.failureMessage),
                        format(comparison.candidate.failureMessage),
                      ],
                    ]}
                  />
                  <Table
                    label="측정값 · 누락은 0이 아닙니다"
                    headers={['측정값 / 종류', '단위', '기준', '비교']}
                    rows={comparison.values.map((v) => [
                      `${v.name} / ${v.kind}`,
                      v.unit,
                      format(v.baseline),
                      format(v.candidate),
                    ])}
                  />
                  <Table
                    label="기록된 조건의 차이"
                    headers={['조건', '기준', '비교', '일치 여부']}
                    rows={comparison.conditions.map((c) => [
                      c.field,
                      <pre>{format(c.baseline)}</pre>,
                      <pre>{format(c.candidate)}</pre>,
                      c.different ? '다름' : '같음',
                    ])}
                  />
                  <p>
                    단일 실행으로 성능 향상을 단정하지 않습니다. 원본 증거와 조건을 함께 보관하세요.
                  </p>
                </>
              )}
            </>
          )}
        </main>
      </div>
    </>
  );
}
createRoot(document.getElementById('root')!).render(<App />);
