import { useState } from 'react';
import { cancelMeasurement } from './series';

/** Asks the server to stop; the page keeps polling and shows the final INTERRUPTED state when it lands. */
export function CancelButton({ kind, id }: { kind: 'run-series' | 'run-sweeps'; id: string }) {
  const [state, setState] = useState<'idle' | 'busy' | 'sent' | string>('idle');
  return (
    <div className="actions">
      <button
        disabled={state === 'busy' || state === 'sent'}
        onClick={() => {
          setState('busy');
          cancelMeasurement(kind, id)
            .then(() => setState('sent'))
            .catch((e) => setState(e instanceof Error ? e.message : String(e)));
        }}
      >
        {kind === 'run-series' ? '반복 측정 중지' : '스윕 중지'}
      </button>
      {state === 'sent' && (
        <span role="status" className="note">
          중지 요청을 보냈습니다. 실행 중인 Run이 끝나면 멈춥니다.
        </span>
      )}
      {!['idle', 'busy', 'sent'].includes(state) && (
        <span role="alert" className="error">
          {state}
        </span>
      )}
    </div>
  );
}
