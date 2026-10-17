import type { MetricPage } from './api';
import { CHART_POINT_LIMIT } from './chartData';

/** Deduplicate by persisted sequence and retain only the latest bounded window. */
export function appendMetricWindow(current: MetricPage['metrics'], incoming: MetricPage['metrics']) {
  const bySequence = new Map(current.map(point => [point.sequence, point]));
  for (const point of incoming) bySequence.set(point.sequence, point);
  return [...bySequence.values()].sort((a, b) => a.sequence - b.sequence).slice(-CHART_POINT_LIMIT);
}

export function advancedMetricCursor(after: number, page: MetricPage): number {
  const last = page.metrics.reduce((cursor, row) => Math.max(cursor, row.sequence), after);
  const next = Math.max(last, page.nextAfter ?? after);
  if (!Number.isSafeInteger(next) || next < after || (page.nextAfter !== null && next <= after))
    throw new Error('메트릭 cursor가 전진하지 않습니다. 다시 따라가기를 시작하세요.');
  return next;
}
