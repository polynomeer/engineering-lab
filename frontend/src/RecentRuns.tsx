import { useEffect, useState } from 'react';
import { get, type Run } from './api';
import { Badge, Table } from './components';

/** A bounded related-run preview. It does not imply a global status aggregate. */
export function RecentRuns({ run }: { run: Run }) {
  const [runs, setRuns] = useState<Run[]>([]);
  const [error, setError] = useState('');
  const [selection, setSelection] = useState<string[]>([]);
  useEffect(() => {
    const controller = new AbortController();
    setRuns([]);
    setError('');
    setSelection([]);
    get<{ runs: Run[] }>(
      '/runs?' + new URLSearchParams({ experimentType: run.experimentType, limit: '5' }),
      controller.signal,
    )
      .then((page) => {
        if (!controller.signal.aborted) setRuns(page.runs.slice(0, 5));
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(e.message);
      });
    return () => controller.abort();
  }, [run.runId, run.experimentType, run.status]);
  return (
    <section className="panel recent-runs" aria-label="관련 실행">
      <div className="section-heading">
        <h2>최근 실행</h2>
        <a href={'/app/runs?experiment=' + encodeURIComponent(run.experimentType)}>
          이 실험의 전체 기록 →
        </a>
      </div>
      <p className="note">같은 실험의 최근 5개 · 비교 전에 입력·환경·측정 조건을 확인하세요.</p>
      {error ? (
        <p role="status">최근 실행 조회 실패 · {error}</p>
      ) : runs.length ? (
        <>
          <Table
            label="관련 실행 · 최신순"
            headers={['비교 선택', '시나리오 / Run', '상태', '소요 시간', '생성 시각']}
            rows={runs.map((item) => [
              <label className="compare-check">
                <input
                  type="checkbox"
                  aria-label={item.scenarioId + ' 비교 선택'}
                  checked={selection.includes(item.runId)}
                  disabled={!selection.includes(item.runId) && selection.length >= 2}
                  onChange={(e) =>
                    setSelection((values) =>
                      e.target.checked
                        ? [...values, item.runId]
                        : values.filter((id) => id !== item.runId),
                    )
                  }
                />
              </label>,
              <>
                <a href={'/app/runs/' + encodeURIComponent(item.runId)}>{item.scenarioId}</a>
                <small className="mono">{item.runId}</small>
              </>,
              <Badge run={item} />,
              item.completedAtEpochMs && item.startedAtEpochMs
                ? ((item.completedAtEpochMs - item.startedAtEpochMs) / 1000).toFixed(2) + ' s'
                : '—',
              new Date(item.createdAtEpochMs).toLocaleString(),
            ])}
          />
          {selection.length === 2 && (
            <a
              className="compare-selection"
              href={
                '/app/compare?' +
                new URLSearchParams({ baseline: selection[0], candidate: selection[1] })
              }
            >
              선택한 2개 Run 비교
            </a>
          )}
        </>
      ) : (
        <p className="note">표시할 관련 실행이 없습니다.</p>
      )}
    </section>
  );
}
