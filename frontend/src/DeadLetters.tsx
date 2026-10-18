import { useEffect, useState } from 'react';
import { get, terminal, type Run } from './api';
import { Table } from './components';
import { deadLetterReasons } from './runInsights';

export type DeadLetter = { rowIndex: number; stage: string; reason: string; cells: string[] };
type Page = { runId: string; rows: DeadLetter[]; nextAfter: number | null };

/** Durable validation failures with row context, paged by the server cursor. */
export function DeadLetters({ run, pageSize }: { run: Run; pageSize: number }) {
  const [after, setAfter] = useState(-1);
  const [page, setPage] = useState<Page | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const done = terminal(run);
  useEffect(() => {
    if (!done) return;
    const controller = new AbortController();
    setBusy(true);
    setError('');
    get<Page>(
      `/runs/${encodeURIComponent(run.runId)}/dead-letters?after=${after}&limit=${Math.max(1, Math.min(pageSize, 100))}`,
      controller.signal,
    )
      .then((value) => {
        if (!controller.signal.aborted) setPage(value);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!controller.signal.aborted) setBusy(false);
      });
    return () => controller.abort();
  }, [run.runId, done, after, pageSize, revision]);
  const reasons = page ? deadLetterReasons(page.rows) : [];
  return (
    <section className="panel dead-letters" aria-labelledby="dead-letters-title">
      <div className="section-heading">
        <h2 id="dead-letters-title">Dead-letter</h2>
        <span className="note">검증에 실패해 저장하지 않은 행 · 행 문맥과 사유</span>
      </div>
      {!done && <p>실행 종료 후 dead-letter를 조회합니다.</p>}
      {busy && <p role="status">dead-letter 조회 중…</p>}
      {error && (
        <div role="alert" className="error">
          dead-letter 조회 실패: {error}{' '}
          <button onClick={() => setRevision((n) => n + 1)}>dead-letter 다시 조회</button>
        </div>
      )}
      {page && page.rows.length === 0 && after === -1 && (
        <p>이 실행에는 dead-letter가 없습니다.</p>
      )}
      {page && page.rows.length > 0 && (
        <>
          <ul className="reason-chips" aria-label="현재 페이지 사유별 건수">
            {reasons.map((r) => (
              <li key={r.reason}>
                <code>{r.reason}</code> <span className="mono">{r.count}</span>
              </li>
            ))}
          </ul>
          <Table
            label="Dead-letter 행 · 현재 페이지"
            headers={['Excel 행 (0 기반)', '단계', '사유', '원본 셀']}
            rows={page.rows.map((row) => [
              <span className="mono">{row.rowIndex}</span>,
              <span className="mono">{row.stage}</span>,
              row.reason,
              <code className="dead-letter-cells">
                {row.cells.length ? row.cells.map((c) => JSON.stringify(c)).join(', ') : '빈 행'}
              </code>,
            ])}
          />
        </>
      )}
      {page && (after !== -1 || page.nextAfter !== null) && (
        <div className="actions">
          <button disabled={busy || after === -1} onClick={() => setAfter(-1)}>
            첫 dead-letter
          </button>
          {page.nextAfter !== null && (
            <button disabled={busy} onClick={() => setAfter(page.nextAfter!)}>
              다음 dead-letter
            </button>
          )}
        </div>
      )}
    </section>
  );
}
