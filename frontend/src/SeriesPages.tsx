import { BundleButton } from './bundle';
import { useEffect, useState } from 'react';
import { apiUrl, get, type Descriptor } from './api';
import { Table } from './components';
import { SweepList } from './SweepPage';
import { seriesComparisonDraft } from './findingDraft';
import { download } from './execution';
import {
  activeSeries,
  directionLabels,
  overallLabels,
  pinBaseline,
  verdictLabels,
  type RegressionCheck,
  rangeScale,
  separationLabels,
  seriesProgress,
  type Series,
  type SeriesComparison,
  type SeriesView,
  type Statistics,
} from './series';

const number = (v: number | null, digits = 2) =>
  v === null ? '—' : v.toLocaleString('ko-KR', { maximumFractionDigits: digits });
const statusLabel: Record<string, string> = {
  QUEUED: '대기',
  RUNNING: '실행 중',
  SUCCEEDED: '성공',
  FAILED: '실패',
  INTERRUPTED: '중단',
};
function SeriesBadge({ status }: { status: string | null }) {
  const value = status ?? 'QUEUED';
  return <span className={'badge ' + value.toLowerCase()}>{statusLabel[value] ?? value}</span>;
}
const seriesLink = (id: string) => '/app/series/' + encodeURIComponent(id);

function useResource<T>(path: string | null, poll: (value: T) => boolean) {
  const [value, setValue] = useState<T | null>(null);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const load = () =>
      get<T>(path, controller.signal)
        .then((next) => {
          if (controller.signal.aborted) return;
          setValue(next);
          setError('');
          if (poll(next) && !document.hidden) timer = setTimeout(load, 2000);
        })
        .catch((e) => {
          if (!controller.signal.aborted) setError(e instanceof Error ? e.message : String(e));
        });
    void load();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
    // poll is a pure predicate supplied inline by callers.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, revision]);
  return { value, error, retry: () => setRevision((n) => n + 1) };
}

/** Recent series with a two-series comparison picker. */
export function SeriesList({ catalog, baseline }: { catalog: Descriptor[]; baseline?: string | null }) {
  const { value, error, retry } = useResource<Series[]>('/run-series?limit=50', (list) =>
    list.some((s) => activeSeries(s.status)),
  );
  const title = (id: string) => catalog.find((d) => d.id === id)?.title ?? id;
  return (
    <section className="series-page" aria-label="반복 측정 목록">
      <p className="note">
        같은 정의를 여러 번 실행해 measure의 분포를 봅니다. 실험 실행 화면에서 반복 횟수를 2 이상으로
        지정하면 묶음이 만들어집니다.
      </p>
      {error && (
        <div role="alert" className="error">
          반복 측정 조회 실패: {error} <button onClick={retry}>다시 조회</button>
        </div>
      )}
      <SweepList catalog={catalog} />
      {value && value.length === 0 && <p>아직 반복 측정이 없습니다.</p>}
      {value && value.length > 0 && (
        <>
          <Table
            label="반복 측정 · 최신순"
            headers={['시나리오 / 묶음', '실험', '반복', '상태', '생성 시각']}
            rows={value.map((s) => [
              <>
                <a href={seriesLink(s.seriesId)}>{s.scenarioId}</a>
                <small className="mono">{s.seriesId}</small>
              </>,
              title(s.experimentType),
              <span className="mono">
                {s.repetitions}회{s.warmup ? ` + warmup ${s.warmup}` : ''}
              </span>,
              <SeriesBadge status={s.status} />,
              new Date(s.createdAtEpochMs).toLocaleString(),
            ])}
          />
          <form action="/app/series/compare" className="series-compare-form">
            <label>
              기준 묶음
              <select name="baseline" required defaultValue={baseline ?? ''}>
                <option value="" disabled>선택</option>
                {value.map((s) => (
                  <option key={s.seriesId} value={s.seriesId}>
                    {s.scenarioId} · {s.seriesId.slice(0, 8)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              비교 묶음
              <select name="candidate" required defaultValue="">
                <option value="" disabled>선택</option>
                {value.map((s) => (
                  <option key={s.seriesId} value={s.seriesId}>
                    {s.scenarioId} · {s.seriesId.slice(0, 8)}
                  </option>
                ))}
              </select>
            </label>
            <button type="submit">묶음 비교</button>
          </form>
        </>
      )}
    </section>
  );
}

function StatisticsRow({ stats, unit }: { stats: Statistics; unit: string }) {
  return (
    <>
      <span className="mono">{number(stats.median)}</span> {unit}
      <small className="mono">
        n={stats.count} · {number(stats.min)}–{number(stats.max)}
        {stats.coefficientOfVariation !== null && ` · CV ${number(stats.coefficientOfVariation * 100, 1)}%`}
      </small>
    </>
  );
}

/** Conditions that make the distribution less trustworthy; shown above it, never folded into the numbers. */
function SeriesCautions({ view }: { view: SeriesView }) {
  const overlapping = view.overlappingRunIds ?? [];
  const stability = view.stability;
  const noisy = stability?.highVariationRoles ?? [];
  const load =
    stability && stability.load1mMax !== null
      ? `측정 반복의 시작·종료 시점 1분 부하 ${stability.load1mMin?.toFixed(2)}–${stability.load1mMax.toFixed(2)}` +
        (stability.availableProcessors ? ` (CPU ${stability.availableProcessors}개)` : '')
      : null;
  if (!overlapping.length && !noisy.length) return null;
  return (
    <section className="panel series-cautions" role="note" aria-label="측정 환경 주의">
      <h2>측정 환경 주의</h2>
      {noisy.length > 0 && (
        <p>
          변동 계수가 {Math.round((stability?.variationThreshold ?? 0.1) * 100)}%를 넘는 measure:{' '}
          <span className="mono">{noisy.join(', ')}</span>. 반복 사이 결과가 흔들려 차이를 판단하기 어렵습니다.
          {load && ` ${load}.`} 반복 수를 늘리거나 다른 작업이 없는 때 다시 측정하세요.
        </p>
      )}
      {overlapping.length > 0 && (
        <>
          <p>
            반복이 실행되는 동안 다른 Run {overlapping.length}개가 같은 서버에서 실행됐습니다. 이 묶음의 값에는 그
            부하가 섞여 있을 수 있습니다.
          </p>
          <ul className="mono">
            {overlapping.map((id) => (
              <li key={id}>
                <a href={'/app/runs/' + encodeURIComponent(id)}>{id}</a>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

export function SeriesDetail({ id }: { id: string }) {
  const { value, error, retry } = useResource<SeriesView>(
    '/run-series/' + encodeURIComponent(id),
    (v) => activeSeries(v.series.status),
  );
  if (error)
    return (
      <div role="alert" className="error">
        반복 측정 조회 실패: {error} <button onClick={retry}>다시 조회</button>
      </div>
    );
  if (!value) return <p role="status">반복 측정을 불러오는 중…</p>;
  const { series } = value;
  const progress = seriesProgress(value);
  return (
    <section className="series-page" aria-label="반복 측정 상세">
      <section className="run-strip" aria-label="선택한 반복 측정">
        <SeriesBadge status={series.status} />
        <h2>{series.scenarioId}</h2>
        <span className="strip-field">
          <small>묶음 ID</small>
          <span className="mono">{series.seriesId}</span>
        </span>
        <span className="strip-field">
          <small>반복</small>
          <span className="mono">
            {series.repetitions}회{series.warmup ? ` + warmup ${series.warmup}` : ''}
          </span>
        </span>
        <span className="strip-field">
          <small>진행</small>
          <span className="mono">
            {progress.finished} / {progress.total}
          </span>
        </span>
        <span className="strip-field">
          <small>정의 SHA-256</small>
          <span className="mono" title={series.definitionSha256}>
            {series.definitionSha256.slice(0, 12)}
          </span>
        </span>
      </section>
      <div
        className="series-progress"
        role="progressbar"
        aria-label="반복 진행"
        aria-valuemin={0}
        aria-valuemax={progress.total}
        aria-valuenow={progress.finished}
      >
        <i style={{ width: (progress.finished / progress.total) * 100 + '%' }} />
      </div>
      {series.failureMessage && <p className="error">{series.failureMessage}</p>}
      <SeriesCautions view={value} />
      <section className="panel">
        <div className="section-heading">
          <h2>measure 분포</h2>
          <span className="note">
            성공한 측정 반복 {value.measuredSucceeded}개 · warmup 제외 · 실행 당시 측정 계약 기준
          </span>
        </div>
        {value.measures.length === 0 ? (
          <p>
            {activeSeries(series.status)
              ? '성공한 측정 반복이 생기면 분포를 표시합니다.'
              : '측정 계약에 성능 measure가 없거나 성공한 측정 반복이 없습니다.'}
          </p>
        ) : (
          <Table
            label="measure별 통계"
            headers={['measure', '중앙값', 'p95', '평균 ± 표준편차', '범위']}
            rows={value.measures.map((m) => [
              <>
                {m.role}
                <small className="mono">
                  {m.name} ({m.unit})
                </small>
              </>,
              <span className="mono">{number(m.statistics.median)}</span>,
              <span className="mono">{number(m.statistics.p95)}</span>,
              <span className="mono">
                {number(m.statistics.mean)}
                {m.statistics.standardDeviation !== null && ` ± ${number(m.statistics.standardDeviation)}`}
              </span>,
              <span className="mono">
                {number(m.statistics.min)}–{number(m.statistics.max)} · n={m.statistics.count}
              </span>,
            ])}
          />
        )}
        <p className="note">
          p95는 nearest rank로 실제 관측값 중 하나입니다. 반복 수가 적으면 p95는 최댓값과 같을 수
          있습니다.
        </p>
      </section>
      <BaselinePanel view={value} />
      {!activeSeries(series.status) && (
        <div className="actions">
          <BundleButton selection={{ series: [series.seriesId] }} />
        </div>
      )}
      <Table
        label="반복 구성"
        headers={['순서', 'Run', '구분', '상태', '소요 시간']}
        rows={value.members.map((m) => [
          <span className="mono">#{m.position}</span>,
          <a className="mono" href={'/app/runs/' + encodeURIComponent(m.runId)}>
            {m.runId}
          </a>,
          m.warmup ? 'warmup · 통계 제외' : '측정',
          <SeriesBadge status={m.status} />,
          m.completedAtEpochMs && m.startedAtEpochMs
            ? ((m.completedAtEpochMs - m.startedAtEpochMs) / 1000).toFixed(2) + ' s'
            : '—',
        ])}
      />
      <div className="actions">
        <a href={'/app/series?compare=' + encodeURIComponent(series.seriesId)}>다른 묶음과 비교</a>
        <a href="/app/series">반복 측정 목록</a>
      </div>
    </section>
  );
}

/** The scenario's pinned baseline, this series' verdict against it, and pinning. */
function BaselinePanel({ view }: { view: SeriesView }) {
  const id = view.series.seriesId;
  const [revision, setRevision] = useState(0);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const done = !activeSeries(view.series.status);
  const check = useResource<RegressionCheck>(
    done ? `/run-series/${encodeURIComponent(id)}/regression?r=${revision}` : null,
    () => false,
  );
  const baselines = useResource<Series[]>(done ? `/run-series/baselines?r=${revision}` : null, () => false);
  const isBaseline = baselines.value?.some((b) => b.seriesId === id) ?? false;
  const eligible = view.series.status === 'SUCCEEDED' && view.measuredSucceeded >= 3;
  async function pin() {
    setBusy(true);
    setMessage('');
    try {
      await pinBaseline(id);
      setMessage('이 묶음을 기준선으로 지정했습니다.');
      setRevision((n) => n + 1);
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }
  if (!done) return null;
  const result = check.value;
  return (
    <section className="panel baseline-panel" aria-labelledby="baseline-title">
      <div className="section-heading">
        <h2 id="baseline-title">회귀 기준선</h2>
        <span className="note">
          같은 실험·시나리오 ID의 기준선과 비교 · 허용 오차 {result?.tolerancePct ?? 5}%
        </span>
      </div>
      {(check.error || baselines.error) && (
        <p role="alert" className="error">
          기준선 조회 실패: {check.error || baselines.error}
        </p>
      )}
      {isBaseline ? (
        <p className="baseline-current">이 묶음이 현재 기준선입니다.</p>
      ) : result ? (
        <p className={'baseline-overall overall-' + result.overall.toLowerCase()}>
          {overallLabels[result.overall]}
          {result.baselineSeriesId && (
            <>
              {' · '}
              <a href={seriesLink(result.baselineSeriesId)}>기준선 묶음 보기</a>
            </>
          )}
        </p>
      ) : null}
      {result && result.measures.length > 0 && !isBaseline && (
        <Table
          label="기준선 대비 판정"
          headers={['measure', '방향', '기준선 → 이 묶음 (중앙값)', '변화', '판정']}
          rows={result.measures.map((m) => [
            <>
              {m.role}
              <small className="mono">{m.name}</small>
            </>,
            directionLabels[m.direction],
            <span className="mono">
              {number(m.baselineMedian)} → {number(m.candidateMedian)} {m.unit}
            </span>,
            <span className="mono">
              {m.changePct === null ? '—' : (m.changePct > 0 ? '+' : '') + number(m.changePct, 1) + '%'}
            </span>,
            <span className={'verdict verdict-' + m.verdict.toLowerCase()}>{verdictLabels[m.verdict]}</span>,
          ])}
        />
      )}
      <div className="actions">
        {!isBaseline && (
          <button onClick={pin} disabled={busy || !eligible}>
            이 묶음을 기준선으로 지정
          </button>
        )}
        {eligible && (
          <a href={apiUrl(`/run-series/${encodeURIComponent(id)}/baseline-file`)} download>
            기준선 파일 (.json)
          </a>
        )}
        {!eligible && <span className="note">성공한 측정 반복이 3회 이상이어야 합니다.</span>}
      </div>
      {eligible && (
        <p className="note">
          기준선 파일은 측정값 원본을 담습니다. 저장소에 커밋하고 <code>lab run … --check-baseline-file</code>로 CI에서 같은
          판정을 할 수 있습니다. 측정한 기계와 다른 환경이면 판정 결과에 주의 문구가 붙습니다.
        </p>
      )}
      {message && <p role="status">{message}</p>}
    </section>
  );
}

function RangeBars({ baseline, candidate, unit }: { baseline: Statistics; candidate: Statistics; unit: string }) {
  const scale = rangeScale(baseline, candidate);
  if (!scale) return <span className="note">값 없음</span>;
  const bar = (stats: Statistics, side: 'baseline' | 'candidate') =>
    stats.min === null || stats.max === null || stats.median === null ? null : (
      <span className={'range-row ' + side}>
        <i
          className="range-span"
          style={{ left: scale.at(stats.min) + '%', width: Math.max(0.5, scale.at(stats.max) - scale.at(stats.min)) + '%' }}
        />
        <i className="range-median" style={{ left: scale.at(stats.median) + '%' }} />
      </span>
    );
  return (
    <span
      className="range-bars"
      role="img"
      aria-label={`A 범위 ${number(baseline.min)}–${number(baseline.max)}, B 범위 ${number(candidate.min)}–${number(candidate.max)} ${unit}`}
    >
      {bar(baseline, 'baseline')}
      {bar(candidate, 'candidate')}
    </span>
  );
}

export function SeriesCompare({ baseline, candidate }: { baseline: string; candidate: string }) {
  const { value, error, retry } = useResource<SeriesComparison>(
    '/run-series/compare?' + new URLSearchParams({ baseline, candidate }),
    () => false,
  );
  if (error)
    return (
      <div role="alert" className="error">
        묶음 비교 실패: {error} <button onClick={retry}>다시 조회</button>
      </div>
    );
  if (!value) return <p role="status">묶음 비교를 불러오는 중…</p>;
  const sides = [
    ['A · 기준', value.baseline, 'baseline'],
    ['B · 비교', value.candidate, 'candidate'],
  ] as const;
  return (
    <section className="series-page comparison-overview" aria-label="반복 측정 비교">
      <div className="comparison-sides">
        {sides.map(([label, view, side]) => (
          <a key={side} className={'comparison-side ' + side} href={seriesLink(view.series.seriesId)}>
            <span className="badge mono">{label}</span>
            <span>
              <strong>{view.series.scenarioId}</strong>
              <small className="mono">
                성공 측정 {view.measuredSucceeded} / {view.series.repetitions}회
              </small>
            </span>
            <SeriesBadge status={view.series.status} />
          </a>
        ))}
      </div>
      <div className="actions">
        <button
          onClick={() =>
            download(
              `finding-draft-${value.baseline.series.seriesId.slice(0, 8)}-${value.candidate.series.seriesId.slice(0, 8)}.md`,
              seriesComparisonDraft(value),
              'text/markdown;charset=utf-8',
            )
          }
        >
          Finding 초안 (.md)
        </button>
        <BundleButton
          selection={{ series: [value.baseline.series.seriesId, value.candidate.series.seriesId] }}
          draft={() => seriesComparisonDraft(value)}
        />
      </div>
      <p className="note">
        {value.sameDefinition
          ? '두 묶음의 시나리오 정의가 같습니다. 같은 조건의 재현성을 확인하는 비교입니다.'
          : '시나리오 정의가 다릅니다. 파라미터 차이를 각 묶음의 Run 상세에서 확인하세요.'}{' '}
        판정은 관측 범위의 겹침만 봅니다. 통계적 유의성 검정이 아닙니다.
      </p>
      {value.measures.length === 0 ? (
        <p>두 묶음에 공통으로 비교할 수 있는 measure가 없습니다.</p>
      ) : (
        <div className="panel">
          <div className="section-heading">
            <h2>measure별 분포 비교</h2>
            <span className="note">막대 = 관측 범위(min–max), 점 = 중앙값, 같은 축</span>
          </div>
          <ul className="series-measures">
            {value.measures.map((m) => (
              <li key={m.role}>
                <span>
                  <strong>{m.role}</strong>
                  <small className="mono">
                    {m.name} ({m.unit})
                  </small>
                </span>
                <span className="series-sides">
                  <span>
                    A <StatisticsRow stats={m.baseline} unit={m.unit} />
                  </span>
                  <span>
                    B <StatisticsRow stats={m.candidate} unit={m.unit} />
                  </span>
                </span>
                <RangeBars baseline={m.baseline} candidate={m.candidate} unit={m.unit} />
                <span className={'separation separation-' + m.separation.toLowerCase()}>
                  <span className="mono">
                    {m.medianChangePct === null
                      ? '—'
                      : (m.medianChangePct > 0 ? '+' : '') + number(m.medianChangePct, 1) + '%'}
                  </span>
                  <small>{separationLabels[m.separation]}</small>
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
