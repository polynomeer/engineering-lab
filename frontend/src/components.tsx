import React from 'react';
import { apiUrl, terminal, type Run } from './api';
export const format = (value: unknown): string =>
  value == null
    ? '미기록'
    : typeof value === 'object'
      ? JSON.stringify(value, null, 2)
      : String(value);
export function Table({
  label,
  headers,
  rows,
}: {
  label: string;
  headers: string[];
  rows: React.ReactNode[][];
}) {
  return (
    <div className="table-wrap" tabIndex={0} aria-label={label}>
      <table>
        <caption>{label}</caption>
        <thead>
          <tr>
            {headers.map((h) => (
              <th key={h} scope="col">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i}>
              {row.map((value, j) =>
                j === 0 ? (
                  <th scope="row" key={j}>
                    {value}
                  </th>
                ) : (
                  <td key={j}>{value}</td>
                ),
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
export function Raw({ label, value }: { label: string; value: unknown }) {
  return (
    <details>
      <summary>{label}</summary>
      <pre>{format(value)}</pre>
    </details>
  );
}
export function Badge({ run }: { run: Run }) {
  const label: Record<string, string> = { RUNNING: '실행 중', SUCCEEDED: '성공', FAILED: '실패' };
  return <span title={run.status} className={'badge ' + run.status.toLowerCase()}>{label[run.status] ?? run.status}</span>;
}
export function Actions({ run }: { run: Run }) {
  return (
    <div className="actions">
      <a href={'/app/compare?baseline=' + encodeURIComponent(run.runId)}>비교 기준으로 선택</a>
      {terminal(run) && (
        <a href={apiUrl(`/runs/${encodeURIComponent(run.runId)}/export`)}>증거 내보내기 (NDJSON)</a>
      )}
    </div>
  );
}
