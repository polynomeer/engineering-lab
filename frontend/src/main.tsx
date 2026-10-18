import { PatternExplorer, usePatternCatalog } from './PatternExplorer';
import {
  CubeIcon,
  PlayIcon,
  ReaderIcon,
  BarChartIcon,
  GearIcon,
  ArchiveIcon,
  LightningBoltIcon,
  LayersIcon,
} from '@radix-ui/react-icons';
import { SeriesCompare, SeriesDetail, SeriesList } from './SeriesPages';
import { capabilities as parseCapabilities } from './capabilities';
import { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { get, optional, parseRoute, type Descriptor, type Module } from './api';
import './style.css';
import { useRouteData } from './useRouteData';
import { RunHistory } from './pages/RunHistory';
import { RunDetail } from './pages/RunDetail';
import { Compare } from './pages/Compare';
import { SettingsPage } from './SettingsPage';
import { loadSettings, saveSettings } from './settings';
import { DatabaseTool, IoEchoTool } from './LegacyTools';
import { ExecutionPage } from './ExecutionForm';
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
function App() {
  const [modules, setModules] = useState<Module[]>([]),
    [catalog, setCatalog] = useState<Descriptor[]>([]);
  const [features, setFeatures] = useState<Record<string, boolean>>({});
  const [bootstrapError, setBootstrapError] = useState('');
  const [legacy, setLegacy] = useState(false);
  const [after, setAfter] = useState(-1),
    [refresh, setRefresh] = useState(0);
  const [options, setOptions] = useState({
    pageSize: 20,
    artifactDownloadMaxBytes: 0,
  });
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
  const { page, run, artifacts, metrics, comparison, error, loading } = useRouteData(
    route,
    options.pageSize,
    refresh,
    after,
  );
  useEffect(() => {
    const visible = () => {
      if (!document.hidden) setRefresh((n) => n + 1);
    };
    document.addEventListener('visibilitychange', visible);
    return () => document.removeEventListener('visibilitychange', visible);
  }, []);
  const patternResource = usePatternCatalog(refresh);
  useEffect(() => {
    const desktop = window.matchMedia('(min-width: 1024px)');
    const restoreDesktopNavigation = () => {
      const drawer = document.querySelector<HTMLDetailsElement>('.navigation-drawer');
      if (desktop.matches && drawer) drawer.open = true;
    };
    desktop.addEventListener('change', restoreDesktopNavigation);
    return () => desktop.removeEventListener('change', restoreDesktopNavigation);
  }, []);
  return (
    <>
      <a className="skip" href="#content">
        본문으로 이동
      </a>
      <div className="layout">
        <aside className="sidebar">
          <a className="sidebar-brand" href="/app/catalog">
            <CubeIcon width={24} height={24} aria-hidden="true" />
            Engineering Lab<small>EXPERIMENT OBSERVATORY</small>
          </a>
          <details
            className="navigation-drawer"
            open={window.innerWidth >= 1024}
            onKeyDown={(event) => {
              if (event.key === 'Escape' && window.innerWidth < 1024) {
                event.currentTarget.open = false;
                event.currentTarget.querySelector('summary')?.focus();
              }
            }}
          >
            <summary>메뉴</summary>
            <nav aria-label="대시보드 탐색">
              <p className="nav-heading" aria-hidden="true">탐색</p>
              <a href="/app/catalog" aria-current={route.kind === 'catalog' ? 'page' : undefined}>
                <CubeIcon width={18} height={18} aria-hidden="true" />분야별 카탈로그
              </a>
              <a href="/app/career" aria-current={route.kind === 'career' ? 'page' : undefined}>
                <ArchiveIcon width={18} height={18} aria-hidden="true" />경험 사례
              </a>
              <p className="nav-heading" aria-hidden="true">실행</p>
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
              <a
                href="/app/series"
                aria-current={route.kind.startsWith('series') ? 'page' : undefined}
              >
                <LayersIcon width={18} height={18} aria-hidden="true" />
                반복 측정
              </a>
              <a href="/app/compare" aria-current={route.kind === 'compare' ? 'page' : undefined}>
                <BarChartIcon width={18} height={18} aria-hidden="true" />
                Run 비교
              </a>
            </nav>
            <details className="navigation-group">
              <summary>분야별 찾아보기</summary>
              <nav aria-label="분야 바로가기">
                {patternResource.metadata?.categories.map(category => <a key={category.id}
                  href={'/app/catalog?category=' + encodeURIComponent(category.id)}
                  aria-current={route.kind === 'catalog' && route.query.get('category') === category.id ? 'page' : undefined}>
                  {category.title}
                </a>)}
                {!patternResource.metadata && <a href="/app/catalog">카탈로그에서 찾아보기</a>}
              </nav>
            </details>
            <details className="navigation-group" open={['database','echo','settings'].includes(route.kind)}>
              <summary>도구·설정</summary>
              <nav aria-label="도구와 설정">
                <a href="/app/tools/ingestion-db"><ArchiveIcon aria-hidden="true" />현재 DB 조회</a>
                <a href="/app/tools/io-echo"><LightningBoltIcon aria-hidden="true" />IO 단건 데모</a>
                <a href="/app/settings"><GearIcon aria-hidden="true" />설정 이전</a>
            <a
              href={
                new URL('/ui?legacy=true', import.meta.env.VITE_LEGACY_BASE ?? location.origin).href
              }
            >
              기존 실험 실행 ↗
            </a>
              </nav>
            </details>
          </details>
          <p
            className={
              'connection-status ' + (bootstrapError ? 'is-error' : loading ? 'is-pending' : 'is-ok')
            }
          >
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
                {route.kind === 'career' ? '경험 사례' : route.kind === 'catalog' ? '분야별 카탈로그' : route.kind === 'settings'
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
                            : route.kind === 'series-list'
                              ? '반복 측정'
                              : route.kind === 'series'
                                ? '반복 측정 상세'
                                : route.kind === 'series-compare'
                                  ? '반복 측정 비교'
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
                  : route.kind === 'execute' || route.kind === 'catalog' || route.kind === 'career'
                    ? '서버에 등록된 실험과 입력 범위'
                    : '저장소에서 조회한 실행 증거'}
          </p>
          {(error || bootstrapError) && (
            <div role="alert" className="error">
              {error || bootstrapError}{' '}
              <button onClick={() => setRefresh((n) => n + 1)}>다시 시도</button>
            </div>
          )}
          {(route.kind === 'catalog' || route.kind === 'career') && <PatternExplorer catalog={catalog} modules={modules} query={route.query} refresh={refresh} resource={patternResource} career={route.kind === 'career'} />}
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
          {route.kind === 'series-list' && (
            <SeriesList catalog={catalog} baseline={route.query.get('compare')} />
          )}
          {route.kind === 'series' && <SeriesDetail id={route.id} key={route.id} />}
          {route.kind === 'series-compare' &&
            (route.query.get('baseline') && route.query.get('candidate') ? (
              <SeriesCompare
                baseline={route.query.get('baseline')!}
                candidate={route.query.get('candidate')!}
              />
            ) : (
              <a href="/app/series">비교할 두 묶음을 반복 측정 목록에서 선택하세요</a>
            ))}
          {route.kind === 'list' && (
            <RunHistory query={route.query} catalog={catalog} page={page} loading={loading} error={error} />
          )}
          {route.kind === 'detail' && run && (
            <RunDetail
              key={run.runId}
              run={run}
              artifacts={artifacts}
              metrics={metrics}
              after={after}
              onAfter={setAfter}
              loading={loading}
              features={features}
              options={options}
            />
          )}
          {route.kind === 'compare' && <Compare query={route.query} comparison={comparison} />}
        </main>
      </div>
    </>
  );
}
createRoot(document.getElementById('root')!).render(<App />);
