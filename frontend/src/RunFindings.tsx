import { useEffect, useState } from 'react';
import { apiUrl, optional } from './api';

type Citation = { file: string; title: string };

/** Finding documents that cite this run; servers without the index answer 404 and the panel stays hidden. */
export function RunFindings({ runId }: { runId: string }) {
  const [citations, setCitations] = useState<Citation[] | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    optional<Citation[]>(`/runs/${encodeURIComponent(runId)}/findings`, controller.signal)
      .then((value) => setCitations(value ?? []))
      .catch(() => setCitations([]));
    return () => controller.abort();
  }, [runId]);
  if (!citations?.length) return null;
  return (
    <section className="panel run-findings" aria-labelledby="run-findings-title">
      <h2 id="run-findings-title">이 Run을 인용한 Finding</h2>
      <ul>
        {citations.map((c) => (
          <li key={c.file}>
            <a href={apiUrl('/findings/' + encodeURIComponent(c.file))}>{c.title}</a>{' '}
            <small className="mono">{c.file}</small>
          </li>
        ))}
      </ul>
      <p className="note">인용된 Run의 JFR·로그 캡처는 보관 정리 대상에서 제외됩니다.</p>
    </section>
  );
}
