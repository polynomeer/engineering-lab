import type { MetricPage, Run } from './api';

export const CHART_POINT_LIMIT = 1000;
type Observation = MetricPage['metrics'][number];
export type ChartSeries = { key: string; label: string };
export type ChartRow = { sequence: number; seconds: number | null; values: (number | null)[] };
export type ChartModel = {
  title: string;
  unit: string;
  description: string;
  series: ChartSeries[];
  rows: ChartRow[];
  percentage?: boolean;
};
const finite = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value);
const nonnegative = (value: unknown): number | null => (finite(value) && value >= 0 ? value : null);
const value = (point: Observation['point'], name: string): number | null =>
  nonnegative(point.gauges[name] ?? point.counters[name]);
const percentage = (
  point: Observation['point'],
  name: string,
  depth: string,
  capacity: unknown,
) => {
  if (name in point.gauges || name in point.counters) {
    const observed = value(point, name);
    return observed !== null && observed <= 100 ? observed : null;
  }
  const size = value(point, depth);
  const cap = typeof capacity === 'string' && capacity.trim() !== '' ? Number(capacity) : NaN;
  return size !== null && Number.isFinite(cap) && cap > 0 && size <= cap
    ? (size / cap) * 100
    : null;
};

/** Exact built-in metric contracts; unknown units/worker clocks are never inferred. */
export function metricCharts(run: Run, input: MetricPage['metrics']): ChartModel[] {
  const pipeline = run.experimentType === 'pipeline.excel-ingestion';
  const queue = run.experimentType === 'concurrency.queue-contention';
  const io = run.experimentType === 'io.endpoint-comparison';
  if (!pipeline && !queue && !io) return [];
  const keys = pipeline
    ? [
        'throughput.producedPerSec',
        'throughput.mappedPerSec',
        'throughput.insertedPerSec',
        'queue.raw.saturationPct',
        'queue.mapped.saturationPct',
        'queue.raw.size',
        'queue.mapped.size',
      ]
    : queue
      ? ['items.consumed', 'queue.depth']
      : ['throughput.totalRequestsPerSec'];
  const observations = input
    .slice(-CHART_POINT_LIMIT)
    .filter(
      ({ point }) =>
        keys.some((key) => key in point.gauges || key in point.counters) ||
        (pipeline && point.labels.source === 'pipeline.progressSnapshot') ||
        (queue && point.labels.experiment === 'queue-contention'),
    );
  // Choose one clock for the entire displayed window. Do not switch origins per point.
  const elapsedClock =
    (pipeline || queue) &&
    observations.length > 0 &&
    observations.every(({ point }) => value(point, 'time.elapsedMs') !== null);
  let lastTime = -1;
  const times = observations.map(({ point }) => {
    const ms = elapsedClock
      ? value(point, 'time.elapsedMs')
      : finite(point.timestampEpochMs) && finite(run.startedAtEpochMs) && run.startedAtEpochMs > 0
        ? point.timestampEpochMs - run.startedAtEpochMs
        : null;
    if (ms === null || !Number.isFinite(ms) || ms < 0 || ms <= lastTime) return null;
    lastTime = ms;
    return ms / 1000;
  });
  const clockNote = elapsedClock
    ? '기록된 monotonic 경과 시간'
    : 'Run 시작과 관측 timestamp의 차이';
  const throughput: ChartModel = {
    title: '처리량 추이',
    unit: pipeline ? 'rows/s' : queue ? 'items/s' : 'requests/s',
    description:
      (pipeline
        ? '기록된 구간 처리량'
        : queue
          ? '인접 누적 items.consumed의 증가량 ÷ 관측 시간 차이로 계산한 구간 평균'
          : '실행 시작 이후 누적 평균 처리량') +
      ' · ' +
      clockNote,
    series: pipeline
      ? [
          { key: 'throughput.producedPerSec', label: 'Parse' },
          { key: 'throughput.mappedPerSec', label: 'Transform' },
          { key: 'throughput.insertedPerSec', label: 'Insert' },
        ]
      : [
          {
            key: queue ? 'items.consumed' : 'throughput.totalRequestsPerSec',
            label: queue ? '소비 처리량 (계산값)' : '전체 요청 (누적 평균)',
          },
        ],
    rows: observations.map(({ sequence, point }, index) => {
      let rate: number | null = null;
      if (queue && index > 0) {
        const previous = observations[index - 1].point.counters['items.consumed'];
        const current = point.counters['items.consumed'];
        const before = times[index - 1];
        const now = times[index];
        if (
          Number.isSafeInteger(previous) &&
          Number.isSafeInteger(current) &&
          previous >= 0 &&
          current >= previous &&
          before !== null &&
          now !== null &&
          now > before
        )
          rate = (current - previous) / (now - before);
      }
      return {
        sequence,
        seconds: times[index],
        values: pipeline
          ? [
              'throughput.producedPerSec',
              'throughput.mappedPerSec',
              'throughput.insertedPerSec',
            ].map((key) => value(point, key))
          : [queue ? rate : value(point, 'throughput.totalRequestsPerSec')],
      };
    }),
  };
  const occupancy: ChartModel = {
    title: '큐 점유율',
    unit: '%',
    percentage: true,
    description: '관측 시점의 점유율 · 큐 최대 깊이는 현재 깊이로 사용하지 않습니다. ' + clockNote,
    series: pipeline
      ? [
          { key: 'queue.raw.saturationPct', label: 'Raw queue' },
          { key: 'queue.mapped.saturationPct', label: 'Mapped queue' },
        ]
      : [{ key: 'queue.depth', label: '작업 큐 (깊이 / 용량)' }],
    rows: observations.map(({ sequence, point }, index) => ({
      sequence,
      seconds: times[index],
      values: pipeline
        ? [
            percentage(
              point,
              'queue.raw.saturationPct',
              'queue.raw.size',
              run.runMetadata.parameters['pipeline.queue.raw-capacity'] ??
                run.runMetadata.parameters.rawCapacity,
            ),
            percentage(
              point,
              'queue.mapped.saturationPct',
              'queue.mapped.size',
              run.runMetadata.parameters['pipeline.queue.mapped-capacity'] ??
                run.runMetadata.parameters.mappedCapacity,
            ),
          ]
        : [
            percentage(
              point,
              'queue.saturationPct',
              'queue.depth',
              run.runMetadata.parameters.queueCapacity,
            ),
          ],
    })),
  };
  if (io) return [throughput];
  if (!pipeline) return [throughput, occupancy];
  const extra = (title: string, unit: string, description: string, series: ChartSeries[]): ChartModel => ({
    title,
    unit,
    description: description + ' · ' + clockNote,
    series,
    rows: observations.map(({ sequence, point }, index) => ({
      sequence,
      seconds: times[index],
      values: series.map((s) => value(point, s.key)),
    })),
  });
  const wait = extra(
    '큐 평균 put 대기',
    'ms',
    'Run 시작 이후 enqueue 1건당 평균 대기(누적 평균) · 구간 값이 아니며 오르면 backpressure가 쌓이는 중',
    [
      { key: 'queue.raw.enqueueWaitMs', label: 'Raw queue' },
      { key: 'queue.mapped.enqueueWaitMs', label: 'Mapped queue' },
    ],
  );
  const lastMax = [...observations].reverse().map(({ point }) => value(point, 'resource.heapMaxMb')).find((v) => v !== null);
  const heap = extra(
    '힙 사용량',
    'MiB',
    '프로세스 전체 힙 · 이 Run만의 사용량이 아님' + (lastMax ? ` · 최대 ${Math.round(lastMax).toLocaleString()} MiB` : ''),
    [
      { key: 'resource.heapUsedMb', label: '사용 중' },
      { key: 'resource.peakHeapMb', label: '관측 최대' },
    ],
  );
  // Older runs never recorded these keys; omit the chart rather than show an empty panel.
  const recorded = (model: ChartModel) => model.rows.some((row) => row.values.some((v) => v !== null));
  return [throughput, occupancy, ...[wait, heap].filter(recorded)];
}

/** A missing/invalid sample starts a new segment rather than interpolating a gap. */
export function chartPath(
  rows: ChartRow[],
  series: number,
  x: (n: number) => number,
  y: (n: number) => number,
): string {
  let connected = false;
  return rows
    .map((row) => {
      const v = row.values[series];
      if (row.seconds === null || v === null) {
        connected = false;
        return '';
      }
      const command = connected ? 'L' : 'M';
      connected = true;
      return `${command}${x(row.seconds).toFixed(2)},${y(v).toFixed(2)}`;
    })
    .join(' ');
}
