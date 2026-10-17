import { useEffect, useRef, useState } from 'react';
import { get, terminal, type MetricPage, type Run } from './api';
import { advancedMetricCursor, appendMetricWindow } from './metricWindow';

const PAGE_BUDGET = 10;
export function useMetricStream(run: Run, enabled: boolean, pageSize: number) {
  const cursor = useRef(-1);
  const [metrics, setMetrics] = useState<MetricPage['metrics']>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [caughtUp, setCaughtUp] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const [revision, retry] = useState(0);
  useEffect(() => {
    cursor.current = -1;
    setMetrics([]);
    setError('');
    setUpdatedAt(null);
    setCaughtUp(false);
  }, [run.runId]);
  useEffect(() => {
    if (!enabled) { setBusy(false); return; }
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let inFlight = false;
    let finished = false;
    let delay = 2000;
    async function load() {
      if (inFlight || document.hidden || controller.signal.aborted || finished) return;
      inFlight = true;
      setBusy(true);
      let more = true;
      try {
        for (let page = 0; page < PAGE_BUDGET && more && !document.hidden; page++) {
          const response = await get<MetricPage>(`/runs/${encodeURIComponent(run.runId)}/metrics?after=${cursor.current}&limit=${Math.max(1, Math.min(pageSize, 100))}`, controller.signal);
          if (controller.signal.aborted) return;
          const next = advancedMetricCursor(cursor.current, response);
          setMetrics(current => appendMetricWindow(current, response.metrics));
          cursor.current = next;
          more = response.nextAfter !== null;
          setUpdatedAt(Date.now());
        }
        setError('');
        setCaughtUp(!more);
        // Terminal status is published after evidence flush; drain any remaining pages first.
        finished = terminal(run) && !more;
        delay = 2000;
      } catch (e) {
        if (!controller.signal.aborted) {
          setError(e instanceof Error ? e.message : String(e));
          delay = Math.min(delay * 2, 15000);
        }
      } finally {
        inFlight = false;
        if (!controller.signal.aborted) {
          setBusy(false);
          if (!document.hidden && !finished) timer = setTimeout(load, delay);
        }
      }
    }
    const visibility = () => {
      clearTimeout(timer);
      if (!document.hidden) void load();
    };
    document.addEventListener('visibilitychange', visibility);
    void load();
    return () => {
      controller.abort();
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', visibility);
    };
  }, [run.runId, run.status, enabled, pageSize, revision]);
  return { metrics, error, busy, caughtUp, updatedAt, retry: () => retry(n => n + 1) };
}
