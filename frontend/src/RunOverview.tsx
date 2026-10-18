import { ArrowRightIcon } from '@radix-ui/react-icons';
import type { Comparison, MetricPage, Run } from './api';
import { Badge } from './components';
import {
  comparisonDeltas,
  conditionSummary,
  pipelineFlow,
  runKpis,
  type FlowQueue,
  type FlowStage,
} from './runInsights';

const number = (value: number, digits = 0) =>
  value.toLocaleString('ko-KR', { maximumFractionDigits: digits });

/** Recorded totals first, so the run outcome is readable before opening charts or raw tables. */
export function KpiStrip({ run }: { run: Run }) {
  const items = runKpis(run);
  if (!items.length) return null;
  return (
    <section className="kpi-strip" aria-label="핵심 지표">
      {items.map((item) => (
        <div key={item.id} className={'kpi' + (item.tone ? ' kpi-' + item.tone : '')}>
          <span className="kpi-label">{item.label}</span>
          <strong className="kpi-value mono">
            {number(item.value, item.value < 100 ? 1 : 0)}
            {item.total !== undefined && <small> / {number(item.total)}</small>}
            {item.unit && <small> {item.unit}</small>}
          </strong>
          {item.total !== undefined && item.total > 0 && (
            <span className="kpi-meter" aria-hidden="true">
              <i style={{ width: Math.min(100, (item.value / item.total) * 100) + '%' }} />
            </span>
          )}
          <span className="kpi-detail">{item.detail}</span>
        </div>
      ))}
    </section>
  );
}

function Stage({ node, step }: { node: FlowStage; step: number }) {
  return (
    <div className="flow-node flow-stage">
      <span className="flow-kicker">STAGE {step}</span>
      <strong>{node.title}</strong>
      <span className="mono flow-workers">{node.workers ?? '작업자 수 미기록'}</span>
      <span className="mono flow-rate">
        {node.rate === null ? '—' : number(node.rate)}
        <small> rows/s</small>
      </span>
    </div>
  );
}

function Queue({ node }: { node: FlowQueue }) {
  const waiting = node.waitMs !== null && node.waitMs >= 1;
  return (
    <div className={'flow-node flow-queue' + (waiting ? ' flow-pressure' : '')}>
      <span className="flow-kicker">BOUNDED QUEUE</span>
      <strong>{node.title}</strong>
      <span
        className="flow-meter"
        role="img"
        aria-label={node.pct === null ? '점유율 미기록' : `점유율 ${number(node.pct)}%`}
      >
        <i style={{ width: (node.pct ?? 0) + '%' }} />
      </span>
      <span className="flow-occupancy mono">
        <span>{node.pct === null ? '—' : number(node.pct) + ' %'}</span>
        <small>
          {node.size === null ? '깊이 미기록' : number(node.size)}
          {node.capacity !== null && ' / ' + number(node.capacity)}
        </small>
      </span>
      <small className="flow-wait">
        {node.waitMs === null
          ? '평균 put 대기 미기록'
          : waiting
            ? `backpressure · 평균 put 대기 ${number(node.waitMs, 1)} ms`
            : node.waitMs > 0
              ? '평균 put 대기 1 ms 미만'
              : '평균 put 대기 없음'}
      </small>
    </div>
  );
}

/** Producer → bounded queues → workers → database, read from one observation. */
export function PipelineFlowPanel({ run, page }: { run: Run; page: MetricPage }) {
  const flow = pipelineFlow(run, page.metrics);
  if (!flow) return null;
  let step = 0;
  return (
    <section className="panel flow-panel" aria-labelledby="pipeline-flow-title">
      <div className="section-heading">
        <h2 id="pipeline-flow-title">
          파이프라인 흐름 · 관측 #{flow.sequence}
          {flow.seconds !== null && ` (${number(flow.seconds, 1)} s)`}
        </h2>
        <span className="note">불러온 관측 구간의 마지막 값</span>
      </div>
      <div className="flow-track">
        {flow.nodes.map((node, i) => (
          <div key={node.id} className="flow-cell">
            {i > 0 && <ArrowRightIcon className="flow-arrow" aria-hidden="true" />}
            {node.kind === 'stage' ? <Stage node={node} step={++step} /> : <Queue node={node} />}
          </div>
        ))}
        <div className="flow-cell">
          <ArrowRightIcon className="flow-arrow" aria-hidden="true" />
          <div className="flow-node flow-sink">
            <strong>DB</strong>
            <span className="mono flow-workers">
              {flow.stored === null ? '저장 수 미기록' : number(flow.stored) + '행 확인'}
            </span>
          </div>
        </div>
      </div>
    </section>
  );
}

/** Recorded-condition overview above the detailed table; it never declares a winner. */
export function ComparisonOverview({ comparison }: { comparison: Comparison }) {
  const conditions = conditionSummary(comparison.conditions);
  const measured = comparisonDeltas(comparison.values).filter((d) => d.change !== null);
  const deltas = measured
    .filter((d) => d.change !== 0)
    .sort((a, b) => Math.abs(b.change!) - Math.abs(a.change!));
  const unchanged = measured.length - deltas.length;
  const sides = [
    ['A · 기준', comparison.baseline, 'baseline'],
    ['B · 비교', comparison.candidate, 'candidate'],
  ] as const;
  return (
    <section className="comparison-overview" aria-label="비교 개요">
      <div className="comparison-sides">
        {sides.map(([label, run, side]) => (
          <a key={side} className={'comparison-side ' + side} href={'/app/runs/' + encodeURIComponent(run.runId)}>
            <span className="badge mono">{label}</span>
            <span>
              <strong>{run.scenarioId}</strong>
              <small className="mono">{run.runId}</small>
            </span>
            <Badge run={run} />
          </a>
        ))}
      </div>
      <div className="panel">
        <div className="section-heading">
          <h2>비교 조건 확인</h2>
          <span className={'badge ' + (conditions.different.length ? 'running' : 'succeeded')}>
            {conditions.different.length
              ? `조건 차이 ${conditions.different.length}개`
              : `기록된 조건 ${conditions.total}개 일치`}
          </span>
        </div>
        <ul className="condition-chips">
          {conditions.different.map((field) => (
            <li key={field} className="condition-different">
              <code>{field}</code> 다름
            </li>
          ))}
          <li className="condition-same">같은 조건 {conditions.same}개</li>
        </ul>
      </div>
      {measured.length > 0 && (
        <div className="panel">
          <div className="section-heading">
            <h2>변화율</h2>
            <span className="note">B의 A 대비 변화 · 개선 여부는 지표 의미와 함께 판단합니다</span>
          </div>
          <ul className="delta-list">
            {deltas.map((d) => (
              <li key={d.name}>
                <span>
                  <code>{d.name}</code>
                  <small>
                    {number(d.baseline!, 2)} → {number(d.candidate!, 2)} {d.unit}
                  </small>
                </span>
                <span className="delta-bar" aria-hidden="true">
                  <i
                    className={d.change! < 0 ? 'negative' : 'positive'}
                    style={{ width: Math.min(50, Math.abs(d.change!) / 2) + '%' }}
                  />
                </span>
                <span className="mono delta-value">
                  {d.change! > 0 ? '+' : ''}
                  {number(d.change!, 1)}%
                </span>
              </li>
            ))}
          </ul>
          {unchanged > 0 && <p className="note">변화 없는 지표 {unchanged}개 · 기준값이 0이거나 누락된 지표는 아래 표에서 확인합니다.</p>}
        </div>
      )}
    </section>
  );
}
