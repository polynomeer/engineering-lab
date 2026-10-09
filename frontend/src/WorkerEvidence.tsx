import { useEffect, useState, type ReactNode } from 'react';
import { apiUrl, get, terminal, type Run } from './api';
import { Raw, Table } from './components';

type Execution = {
  available: boolean;
  execution: null | {
    definition: { kind: string; moduleId: string; measurementScope: string };
    phase: string;
    evidenceComplete: boolean;
    worker: null | {
      environment: Record<string, unknown>;
      git: Record<string, unknown>;
      artifactSha256: string;
    };
  };
};
type Checks = {
  available: boolean;
  checks: {
    sequence: number;
    checkId: string;
    status: string;
    expected: string;
    observed: string;
    description: string;
  }[];
  nextAfter: number | null;
};
const readinessLabels: Record<string, string> = {
  READY: '실행 준비 완료',
  NOT_BUILT: 'worker 배포 파일 없음',
  INCOMPATIBLE: 'worker 배포 확인 필요',
  DOCKER_UNAVAILABLE: 'Docker 준비 필요',
};
export function ExecutionReadiness({ id, children }: { id: string; children: ReactNode }) {
  const [state, setState] = useState('');
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setState('');
    setError('');
    get<{ readiness: string; executionKind: string }>(
      `/dashboard/v1/experiments/${encodeURIComponent(id)}`,
      controller.signal,
      75000,
    )
      .then((value) => {
        if (!value || typeof value.readiness !== 'string' || !value.readiness)
          throw new Error('준비 상태 응답을 확인할 수 없습니다. 다시 확인하세요.');
        if (!controller.signal.aborted) setState(value.readiness);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(e.message);
      });
    return () => controller.abort();
  }, [id, revision]);
  return (
    <section aria-label="실행 준비 상태">
      <p role="status">
        {state
          ? (readinessLabels[state] ?? '지원하지 않는 준비 상태')
          : error
            ? '준비 상태 확인 실패'
            : '실행 환경 확인 중… 최대 75초가 걸릴 수 있습니다.'}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {(state || error) && (
        <button onClick={() => setRevision((n) => n + 1)}>준비 상태 다시 확인</button>
      )}
      {state !== 'READY' && (state || error) && (
        <p>
          서버의 worker 배포와 Docker 상태를 확인한 뒤 다시 확인하세요. 저장된 실행 기록은 계속
          조회할 수 있습니다.
        </p>
      )}
      <fieldset className="readiness-gate" disabled={state !== 'READY'}>
        <legend>실행 설정</legend>
        {children}
      </fieldset>
    </section>
  );
}
export function WorkerEvidence({
  run,
  features,
  pageSize,
}: {
  run: Run;
  features: Record<string, boolean>;
  pageSize: number;
}) {
  const [execution, setExecution] = useState<Execution | null>(null);
  const [checks, setChecks] = useState<Checks | null>(null);
  const [after, setAfter] = useState(-1);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let delay = 2000;
    async function load() {
      setBusy(true);
      setError('');
      setChecks(null);
      const path = `/dashboard/v1/runs/${encodeURIComponent(run.runId)}`;
      try {
        const [value, page] = await Promise.all([
          features.executionEvidence
            ? get<Execution>(path + '/execution', controller.signal)
            : Promise.resolve(null),
          features.checksRead && terminal(run)
            ? get<Checks>(
                path + `/checks?after=${after}&limit=${Math.max(1, Math.min(pageSize, 100))}`,
                controller.signal,
              )
            : Promise.resolve(null),
        ]);
        if (!controller.signal.aborted) {
          setExecution(value);
          setChecks(page);
          delay = 2000;
        }
      } catch (e) {
        if (!controller.signal.aborted) {
          setExecution(null);
          setError(e instanceof Error ? e.message : String(e));
          delay = Math.min(delay * 2, 15000);
        }
      } finally {
        if (!controller.signal.aborted) {
          setBusy(false);
          if (!terminal(run) && !document.hidden) timer = setTimeout(load, delay);
        }
      }
    }
    const visibility = () => {
      if (document.hidden) clearTimeout(timer);
      else setRevision((n) => n + 1);
    };
    document.addEventListener('visibilitychange', visibility);
    void load();
    return () => {
      controller.abort();
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', visibility);
    };
    // Poll after the evidence request finishes, independently of the Run overview poll.
  }, [run.runId, run.status, features, after, revision, pageSize]);
  const evidence = execution?.execution;
  return (
    <section aria-label="실행 및 검증 증거">
      <h2>실행 환경과 증거 상태</h2>
      {busy && <p role="status">실행 증거 확인 중…</p>}
      {error && (
        <div role="alert" className="error">
          {error} <button onClick={() => setRevision((n) => n + 1)}>증거 다시 조회</button>
        </div>
      )}
      {execution && !execution.available && (
        <p>이 실행에는 확장 실행 정보가 기록되지 않았습니다.</p>
      )}
      {evidence && (
        <>
          <Table
            label="실제 실행 정보"
            headers={['항목', '기록값']}
            rows={[
              ['실행 종류', evidence.definition.kind],
              ['모듈', evidence.definition.moduleId],
              ['측정 범위', evidence.definition.measurementScope],
              ['마지막 기록 단계', evidence.phase],
              [
                '증거 상태',
                evidence.evidenceComplete ? '증거 수집 완료' : '증거 미완료 · 확인된 부분만 표시',
              ],
            ]}
          />
          {evidence.definition.kind === 'LOCAL_JVM' &&
            (evidence.worker ? (
              <>
                <Raw label="Worker JVM / 운영체제 / Git" value={evidence.worker} />
                <p className="mono">배포 SHA-256: {evidence.worker.artifactSha256}</p>
              </>
            ) : (
              <p>Worker 환경은 아직 기록되지 않았습니다. 서버 환경으로 대신 표시하지 않습니다.</p>
            ))}
        </>
      )}
      {features.checksRead && (
        <>
          <h2>검증 결과</h2>
          {!terminal(run) && <p>실행 종료 후 검증 결과를 조회합니다.</p>}
          {checks &&
            (!checks.available ? (
              <p>이 실행에는 검증 정보가 기록되지 않았습니다.</p>
            ) : (
              <>
                <p>
                  PASS는 기록된 기대값 충족을 뜻합니다. 의도한 이상 현상을 재현한 경우에도 PASS일 수
                  있습니다.
                </p>
                <Table
                  label="기대값과 관측값"
                  headers={['검증 / 설명', '판정', '기대값', '관측값']}
                  rows={checks.checks.map((c) => [
                    <>
                      {c.checkId}
                      <small>{c.description}</small>
                    </>,
                    c.status,
                    c.expected,
                    c.observed,
                  ])}
                />
                {!checks.checks.length && (
                  <p>이 페이지에 검증 결과가 없습니다. 빈 결과를 통과로 해석하지 않습니다.</p>
                )}
                <div className="actions">
                  <button disabled={busy || after === -1} onClick={() => setAfter(-1)}>
                    첫 검증
                  </button>
                  {checks.nextAfter !== null && (
                    <button disabled={busy} onClick={() => setAfter(checks.nextAfter!)}>
                      다음 검증
                    </button>
                  )}
                </div>
              </>
            ))}
        </>
      )}
      {features.extendedExport && terminal(run) && (
        <a href={apiUrl(`/dashboard/v1/runs/${encodeURIComponent(run.runId)}/export`)}>
          실행·검증 포함 내보내기 (NDJSON)
        </a>
      )}
    </section>
  );
}
