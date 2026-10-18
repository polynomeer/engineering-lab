import { useEffect, useState } from 'react';
import { get, type Descriptor } from './api';
import { Table } from './components';
import { activeSeries, sweepMeasure, type Sweep, type SweepView } from './series';

const number = (v: number | null | undefined, digits = 2) =>
  v === null || v === undefined ? '—' : v.toLocaleString('ko-KR', { maximumFractionDigits: digits });
const statusLabel: Record<string, string> = {
  QUEUED: '대기',
  RUNNING: '실행 중',
  SUCCEEDED: '성공',
  FAILED: '실패',
  INTERRUPTED: '중단',
};
const Status = ({ status }: { status: string | null | undefined }) => (
  <span className={'badge ' + (status ?? 'queued').toLowerCase()}>
    {statusLabel[status ?? 'QUEUED'] ?? status}
  </span>
);
const label = (v: string | number | boolean) => String(v);

function usePolled<T>(path: string, active: (value: T) => boolean) {
  const [value, setValue] = useState<T | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const load = () =>
      get<T>(path, controller.signal)
        .then((next) => {
          if (controller.signal.aborted) return;
          setValue(next);
          setError('');
          if (active(next) && !document.hidden) timer = setTimeout(load, 2000);
        })
        .catch((e) => {
          if (!controller.signal.aborted) setError(e instanceof Error ? e.message : String(e));
        });
    void load();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
    // active is an inline predicate; the path identifies the resource.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path]);
  return { value, error };
}

export function SweepList({ catalog }: { catalog: Descriptor[] }) {
  const { value, error } = usePolled<Sweep[]>('/run-sweeps?limit=20', (list) =>
    list.some((s) => activeSeries(s.status)),
  );
  const title = (id: string) => catalog.find((d) => d.id === id)?.title ?? id;
  if (error) return <p role="alert" className="error">스윕 조회 실패: {error}</p>;
  if (!value || value.length === 0) return null;
  return (
    <Table
      label="파라미터 스윕 · 최신순"
      headers={['시나리오 / 스윕', '실험', 'grid', '점마다', '상태']}
      rows={value.map((s) => [
        <>
          <a href={'/app/sweeps/' + encodeURIComponent(s.sweepId)}>{s.scenarioId}</a>
          <small className="mono">{s.sweepId}</small>
        </>,
        title(s.experimentType),
        <span className="mono">
          {Object.entries(s.grid)
            .map(([k, v]) => `${k}=${v.map(label).join(',')}`)
            .join(' · ')}
        </span>,
        <span className="mono">
          {s.repetitions}회{s.warmup ? ` + warmup ${s.warmup}` : ''}
        </span>,
        <Status status={s.status} />,
      ])}
    />
  );
}

/** One grid parameter: the median per value with its observed min–max range. */
function LineChart({ view, role, parameter }: { view: SweepView; role: string; parameter: string }) {
  const rows = sweepMeasure(view, role);
  const values = rows.flatMap((r) => (r.stats ? [r.stats.min, r.stats.max, r.stats.median] : [])).filter(
    (v): v is number => v !== null,
  );
  if (!values.length) return <p className="note">아직 그릴 값이 없습니다.</p>;
  const width = 720,
    height = 240,
    left = 72,
    right = 24,
    top = 16,
    bottom = 44;
  const lo = Math.min(0, ...values),
    hi = Math.max(...values) * 1.1 || 1;
  const x = (i: number) => left + ((i + 0.5) / rows.length) * (width - left - right);
  const y = (v: number) => height - bottom - ((v - lo) / (hi - lo)) * (height - top - bottom);
  const unit = rows.find((r) => r.unit)?.unit ?? '';
  const medians = rows
    .map((r, i) => (r.stats?.median !== null && r.stats?.median !== undefined ? `${x(i)},${y(r.stats.median)}` : null))
    .filter(Boolean);
  return (
    <>
    <p className="note">
      {parameter}별 {role} 중앙값({unit}) · 점 = 중앙값, 세로선 = 관측 범위(min–max)
    </p>
    <svg className="sweep-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`${parameter}별 ${role} 중앙값 (${unit})`}>
      {[0, 0.5, 1].map((t) => (
        <g key={t}>
          <line x1={left} x2={width - right} y1={y(lo + (hi - lo) * t)} y2={y(lo + (hi - lo) * t)} className="chart-grid" />
          <text x={left - 8} y={y(lo + (hi - lo) * t) + 4} textAnchor="end">
            {number(lo + (hi - lo) * t, 1)}
          </text>
        </g>
      ))}
      {medians.length > 1 && <polyline points={medians.join(' ')} fill="none" stroke="var(--accent)" strokeWidth="2" />}
      {rows.map((r, i) => (
        <g key={r.point.position}>
          {r.stats?.min !== null && r.stats?.max !== null && r.stats && (
            <line x1={x(i)} x2={x(i)} y1={y(r.stats.min!)} y2={y(r.stats.max!)} stroke="var(--muted)" strokeWidth="2" />
          )}
          {r.stats?.median !== null && r.stats && (
            <circle cx={x(i)} cy={y(r.stats.median!)} r="5" fill="var(--accent)" stroke="var(--panel)" strokeWidth="2">
              <title>
                {parameter}={label(r.point.parameters[parameter])} · 중앙값 {number(r.stats.median)} {unit} · 범위{' '}
                {number(r.stats.min)}–{number(r.stats.max)}
              </title>
            </circle>
          )}
          <text x={x(i)} y={height - bottom + 20} textAnchor="middle">
            {label(r.point.parameters[parameter])}
          </text>
        </g>
      ))}
      <text x={(width + left - right) / 2} y={height - 6} textAnchor="middle">
        {parameter}
      </text>
    </svg>
    </>
  );
}

/** Two grid parameters: a heatmap of medians, darker = larger. */
function Heatmap({ view, role, rowsKey, colsKey }: { view: SweepView; role: string; rowsKey: string; colsKey: string }) {
  const cells = sweepMeasure(view, role);
  const rowValues = view.sweep.grid[rowsKey],
    colValues = view.sweep.grid[colsKey];
  const medians = cells.map((c) => c.stats?.median).filter((v): v is number => v !== null && v !== undefined);
  const lo = Math.min(...medians),
    hi = Math.max(...medians);
  const unit = cells.find((c) => c.unit)?.unit ?? '';
  const at = (r: unknown, c: unknown) =>
    cells.find((cell) => cell.point.parameters[rowsKey] === r && cell.point.parameters[colsKey] === c);
  return (
    <div className="sweep-heatmap-wrap">
      <table className="sweep-heatmap" aria-label={`${rowsKey} × ${colsKey} ${role} 중앙값 (${unit})`}>
        <thead>
          <tr>
            <th scope="col">
              {rowsKey} \ {colsKey}
            </th>
            {colValues.map((c) => (
              <th scope="col" key={label(c)}>
                {label(c)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rowValues.map((r) => (
            <tr key={label(r)}>
              <th scope="row">{label(r)}</th>
              {colValues.map((c) => {
                const median = at(r, c)?.stats?.median;
                const strength =
                  median === null || median === undefined || hi === lo ? 0.15 : 0.12 + ((median - lo) / (hi - lo)) * 0.5;
                return (
                  <td key={label(c)} style={{ background: `rgba(233, 180, 92, ${strength})` }}>
                    <span className="mono">{number(median)}</span>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="note">
        칸 = 중앙값({unit}), 진할수록 큼. 크다는 것이 좋다는 뜻인지는 measure에 따라 다릅니다.
      </p>
    </div>
  );
}

export function SweepDetail({ id }: { id: string }) {
  const { value, error } = usePolled<SweepView>('/run-sweeps/' + encodeURIComponent(id), (v) =>
    activeSeries(v.sweep.status),
  );
  const [role, setRole] = useState('');
  if (error) return <p role="alert" className="error">스윕 조회 실패: {error}</p>;
  if (!value) return <p role="status">스윕을 불러오는 중…</p>;
  const { sweep } = value;
  const keys = Object.keys(sweep.grid);
  const roles = [...new Set(value.points.flatMap((p) => p.series?.measures.map((m) => m.role) ?? []))];
  const selected = roles.includes(role) ? role : roles[0] ?? '';
  const total = keys.reduce((n, k) => n * sweep.grid[k].length, 1);
  const finished = value.points.filter((p) => p.series && !activeSeries(p.series.series.status)).length;
  return (
    <section className="series-page" aria-label="파라미터 스윕 상세">
      <section className="run-strip" aria-label="선택한 스윕">
        <Status status={sweep.status} />
        <h2>{sweep.scenarioId}</h2>
        <span className="strip-field">
          <small>grid</small>
          <span className="mono">
            {keys.map((k) => `${k}=${sweep.grid[k].map(label).join(',')}`).join(' · ')}
          </span>
        </span>
        <span className="strip-field">
          <small>점마다</small>
          <span className="mono">
            {sweep.repetitions}회{sweep.warmup ? ` + warmup ${sweep.warmup}` : ''}
          </span>
        </span>
        <span className="strip-field">
          <small>진행</small>
          <span className="mono">
            {finished} / {total} 점
          </span>
        </span>
      </section>
      {sweep.failureMessage && <p className="error">{sweep.failureMessage}</p>}
      <section className="panel">
        <div className="section-heading">
          <h2>measure별 결과</h2>
          {roles.length > 0 && (
            <label className="sweep-role">
              measure
              <select value={selected} onChange={(e) => setRole(e.target.value)}>
                {roles.map((r) => (
                  <option key={r}>{r}</option>
                ))}
              </select>
            </label>
          )}
        </div>
        {!selected ? (
          <p>성공한 측정 반복이 생기면 결과를 표시합니다.</p>
        ) : keys.length === 1 ? (
          <LineChart view={value} role={selected} parameter={keys[0]} />
        ) : keys.length === 2 ? (
          <Heatmap view={value} role={selected} rowsKey={keys[0]} colsKey={keys[1]} />
        ) : (
          <p className="note">파라미터가 3개 이상이면 아래 표로 비교합니다.</p>
        )}
        <p className="note">점마다 성공한 측정 반복으로 계산합니다. 점 사이 차이도 반복 범위가 겹치면 단정하지 않습니다.</p>
      </section>
      <Table
        label="스윕 점"
        headers={['점', '파라미터', '상태', selected ? `${selected} 중앙값` : 'measure', '범위 · n', '묶음']}
        rows={value.points.map((p) => {
          const m = p.series?.measures.find((x) => x.role === selected);
          return [
            <span className="mono">p{p.position}</span>,
            <span className="mono">
              {Object.entries(p.parameters)
                .map(([k, v]) => `${k}=${label(v)}`)
                .join(' · ')}
            </span>,
            <Status status={p.series?.series.status} />,
            <span className="mono">
              {number(m?.statistics.median)} {m?.unit}
            </span>,
            <span className="mono">
              {m ? `${number(m.statistics.min)}–${number(m.statistics.max)} · n=${m.statistics.count}` : '—'}
            </span>,
            p.series ? <a href={'/app/series/' + encodeURIComponent(p.series.series.seriesId)}>묶음 상세</a> : '—',
          ];
        })}
      />
    </section>
  );
}
