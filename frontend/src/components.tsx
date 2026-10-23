import { bundlePath } from './bundle';
import React, { useState } from 'react';
import { CopyIcon } from '@radix-ui/react-icons';
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
  return (
    <span title={run.status} className={'badge ' + run.status.toLowerCase()}>
      {label[run.status] ?? run.status}
    </span>
  );
}
export function Actions({ run }: { run: Run }) {
  return (
    <div className="actions">
      <a href={'/app/compare?baseline=' + encodeURIComponent(run.runId)}>비교 기준으로 선택</a>
      {terminal(run) && (
        <a href={apiUrl(`/runs/${encodeURIComponent(run.runId)}/export`)}>증거 내보내기 (NDJSON)</a>
      )}
      {terminal(run) && (
        <a href={apiUrl(bundlePath({ run: [run.runId] }))}>증거 묶음 (.zip)</a>
      )}
    </div>
  );
}

export function CopyValue({ label, value }: { label: string; value: unknown }) {
  const [message, setMessage] = useState('');
  const text = typeof value === 'string' && value ? value : null;
  return (
    <div className="copy-value">
      <span>{label}</span>
      <code title={text ?? undefined}>{text ? text.slice(0, 12) : '미기록'}</code>
      {text && (
        <button
          aria-label={label + ' 복사'}
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(text);
              setMessage('복사됨');
            } catch {
              setMessage('복사할 수 없습니다. 원본 상세를 확인하세요.');
            }
          }}
        >
          <CopyIcon aria-hidden="true" />
        </button>
      )}
      {message && <small role="status">{message}</small>}
    </div>
  );
}
