import { useId, useState } from 'react';
import type { MetricPage, Run } from './api';
import { Table } from './components';
import { CHART_POINT_LIMIT, chartPath, metricCharts, type ChartModel } from './chartData';

const number = (value: number) => value.toLocaleString('ko-KR', { maximumFractionDigits: 2 });
const strokes = ['#e9b45c', '#b7c1ca', '#73d3a0'];
const dashes = ['', '7 4', '2 4'];
function TimeChart({ model }: { model: ChartModel }) {
  const titleId = useId();
  const [selected, setSelected] = useState<number | null>(null);
  const valid = model.rows.filter((row) => row.seconds !== null);
  const values = valid.flatMap((row) => row.values.filter((v): v is number => v !== null));
  if (!valid.length || !values.length)
    return (
      <section className="metric-chart empty-chart" aria-label={model.title}>
        <h2>{model.title}</h2>
        <p>현재 관측 페이지에 그래프로 표시할 값이 없습니다.</p>
        <small>{model.description}</small>
        {model.percentage && (
          <p className="note">큐 깊이·양수 용량 또는 유효한 점유율 기록이 필요합니다.</p>
        )}
      </section>
    );
  const width = 720,
    height = 210,
    left = 90,
    right = 26,
    top = 22,
    bottom = 52;
  const xmax = Math.max(0.001, ...valid.map((row) => row.seconds!));
  const peak = Math.max(1, ...values);
  const ymax = model.percentage ? 100 : peak <= Number.MAX_VALUE / 1.1 ? peak * 1.1 : peak;
  const x = (value: number) => left + (value / xmax) * (width - left - right);
  const y = (value: number) => height - bottom - (value / ymax) * (height - top - bottom);
  const index = Math.min(selected ?? model.rows.length - 1, model.rows.length - 1);
  const row = model.rows[index];
  return (
    <section className="metric-chart" aria-label={model.title}>
      <div className="section-heading">
        <h2 id={titleId}>{model.title}</h2>
        <span className="chart-unit">{model.unit}</span>
      </div>
      <p className="note chart-description">{model.description}</p>
      <div className="chart-legend">
        {model.series.map((series, i) => (
          <span key={series.key} title={series.key}>
            <i
              style={{
                borderColor: strokes[i],
                borderTopStyle: i === 0 ? 'solid' : i === 1 ? 'dashed' : 'dotted',
              }}
            />
            {series.label}
          </span>
        ))}
      </div>
      <svg
        className="time-chart"
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-labelledby={titleId}
      >
        <desc>
          가로축은 경과 시간(초), 세로축은 {model.unit}입니다. 결측 구간은 연결하지 않습니다. 아래
          값 표에서 상세를 확인할 수 있습니다.
        </desc>
        {[0, 1, 2, 3, 4].map((tick) => (
          <g key={tick}>
            <line
              x1={left}
              x2={width - right}
              y1={y((ymax * tick) / 4)}
              y2={y((ymax * tick) / 4)}
              className="chart-grid"
            />
            <text x={left - 10} y={y((ymax * tick) / 4) + 4} textAnchor="end">
              {number((ymax * tick) / 4)}
            </text>
            <text x={x((xmax * tick) / 4)} y={height - bottom + 20} textAnchor="middle">
              {number((xmax * tick) / 4)}
            </text>
          </g>
        ))}
        <text x={(width + left - right) / 2} y={height - 8} textAnchor="middle">
          경과 시간 (s)
        </text>
        {model.series.map((series, i) => (
          <g key={series.key}>
            <path
              d={chartPath(model.rows, i, x, y)}
              fill="none"
              stroke={strokes[i]}
              strokeWidth="2"
              strokeDasharray={dashes[i]}
            />
            {model.rows.map((point, j) =>
              point.seconds !== null && point.values[i] !== null ? (
                <circle
                  key={point.sequence}
                  cx={x(point.seconds)}
                  cy={y(point.values[i]!)}
                  r={j === index ? 4 : 2}
                  fill={strokes[i]}
                  onMouseEnter={() => setSelected(j)}
                >
                  <title>
                    {series.label} · {number(point.seconds)} s · {number(point.values[i]!)}{' '}
                    {model.unit}
                  </title>
                </circle>
              ) : null,
            )}
          </g>
        ))}
      </svg>
      <label className="chart-selector">
        관측 지점 선택
        <input
          type="range"
          aria-label={model.title + ' 관측 지점 선택'}
          aria-valuetext={`${row.seconds === null ? '시간 미기록' : number(row.seconds) + ' s'} · ${model.series.map((series, i) => `${series.label}: ${row.values[i] === null || row.seconds === null ? '결측' : number(row.values[i]!) + ' ' + model.unit}`).join(' · ')}`}
          min="0"
          max={Math.max(0, model.rows.length - 1)}
          value={index}
          onChange={(event) => setSelected(Number(event.target.value))}
        />
      </label>
      <output className="chart-readout" aria-live="off">
        #{row.sequence} · {row.seconds === null ? '시간 미기록' : number(row.seconds) + ' s'}
        {model.series.map((series, i) => (
          <span key={series.key}>
            {series.label}:{' '}
            {row.values[i] === null || row.seconds === null
              ? '—'
              : number(row.values[i]!) + ' ' + model.unit}
          </span>
        ))}
      </output>
      {values.length === 1 && (
        <p className="note">유효한 값이 한 개여서 점으로 표시합니다. 추이를 추정하지 않습니다.</p>
      )}
      <details className="chart-data">
        <summary>{model.title} 값 표</summary>
        <Table
          label={model.title + ' 그래프 값'}
          headers={[
            '순서 / 경과 시간',
            ...model.series.map((series) => series.label + ' (' + model.unit + ')'),
          ]}
          rows={model.rows.map((point) => [
            `#${point.sequence} / ${point.seconds === null ? '—' : number(point.seconds) + ' s'}`,
            ...point.values.map((v) => (v === null || point.seconds === null ? '—' : number(v))),
          ])}
        />
      </details>
    </section>
  );
}
export function MetricCharts({ run, page }: { run: Run; page: MetricPage }) {
  const models = metricCharts(run, page.metrics);
  if (!models.length)
    return (
      <p className="note">
        이 실험에는 지원되는 처리량·큐 시계열이 없습니다. 원본 관측과 검증 결과를 확인하세요.
      </p>
    );
  return (
    <section className="metric-charts" aria-label="메트릭 그래프">
      <p className="note chart-window">
        현재 관측 페이지 · {page.metrics.length}개 표본 (그래프 최대{' '}
        {CHART_POINT_LIMIT.toLocaleString()}개) · 전체 Run의 시계열이 아닙니다. 아래 관측 페이지
        버튼으로 구간을 이동하세요.
      </p>
      {models.map((model) => (
        <TimeChart
          key={run.runId + model.title + (page.metrics[0]?.sequence ?? 'empty')}
          model={model}
        />
      ))}
    </section>
  );
}
