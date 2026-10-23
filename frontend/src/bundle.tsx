import { useState } from 'react';
import { ApiError, apiUrl } from './api';

export type BundleSelection = { series?: string[]; run?: string[] };

export function bundlePath(selection: BundleSelection) {
  const query = new URLSearchParams();
  for (const id of selection.series ?? []) query.append('series', id);
  for (const id of selection.run ?? []) query.append('run', id);
  return '/evidence-bundles?' + query.toString();
}

/** GET without a draft, POST with one; the server streams the zip and names it. */
export async function downloadBundle(selection: BundleSelection, draft?: string) {
  const response = await fetch(apiUrl(bundlePath(selection)), {
    method: draft === undefined ? 'GET' : 'POST',
    headers: draft === undefined ? undefined : { 'Content-Type': 'text/markdown; charset=utf-8' },
    body: draft,
    signal: AbortSignal.timeout(300_000),
  });
  if (!response.ok) throw new ApiError(response.status);
  const name = /filename=([^;]+)/.exec(response.headers.get('content-disposition') ?? '')?.[1] ?? 'evidence.zip';
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  URL.revokeObjectURL(url);
}

/** Every run export, verified captures, statistics and SHA256SUMS in one zip; the draft is included when given. */
export function BundleButton({ selection, draft }: { selection: BundleSelection; draft?: () => string }) {
  const [state, setState] = useState<'idle' | 'busy' | string>('idle');
  return (
    <>
      <button
        disabled={state === 'busy'}
        onClick={() => {
          setState('busy');
          downloadBundle(selection, draft?.())
            .then(() => setState('idle'))
            .catch((e) => setState(e instanceof Error ? e.message : String(e)));
        }}
      >
        {state === 'busy' ? '증거 묶음 만드는 중…' : '증거 묶음 (.zip)'}
      </button>
      {state !== 'idle' && state !== 'busy' && (
        <span role="alert" className="error">
          증거 묶음 실패: {state}
        </span>
      )}
    </>
  );
}
