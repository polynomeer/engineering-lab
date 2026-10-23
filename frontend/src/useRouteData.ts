import { useEffect, useState } from 'react';
import {
  ApiError,
  get,
  terminal,
  type Artifact,
  type Comparison,
  type MetricPage,
  type Run,
  type parseRoute,
} from './api';

export type Route = ReturnType<typeof parseRoute>;
export type RunPage = { runs: Run[]; nextBefore: number | null };

/** Loads what the current route needs; an active Run is polled, with backoff on transient errors. */
export function useRouteData(route: Route, pageSize: number, refresh: number, after: number) {
  const [error, setError] = useState(''),
    [loading, setLoading] = useState(true);
  const [page, setPage] = useState<RunPage>({ runs: [], nextBefore: null });
  const [run, setRun] = useState<Run | null>(null),
    [artifacts, setArtifacts] = useState<Artifact[]>([]);
  const [metrics, setMetrics] = useState<MetricPage>({ metrics: [], nextAfter: null });
  const [comparison, setComparison] = useState<Comparison | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let delay = 2000;
    async function load() {
      try {
        setLoading(true);
        if (route.kind === 'list') {
          const params = new URLSearchParams({
            limit: String(Math.min(pageSize, 100)),
          });
          for (const key of ['experiment', 'scenario', 'status', 'before'])
            if (route.query.get(key))
              params.set(key === 'experiment' ? 'experimentType' : key, route.query.get(key)!);
          setPage(await get('/runs?' + params, controller.signal));
        } else if (route.kind === 'detail') {
          const id = encodeURIComponent(route.id);
          const [detail, refs, observations] = await Promise.all([
            get<Run>('/runs/' + id, controller.signal),
            get<Artifact[]>(`/runs/${id}/artifacts`, controller.signal),
            get<MetricPage>(
              `/runs/${id}/metrics?after=${after}&limit=${Math.min(pageSize, 100)}`,
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
  }, [refresh, after, pageSize]);
  return { page, run, artifacts, metrics, comparison, error, loading };
}
