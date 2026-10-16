import { useEffect, useState, type ReactNode } from 'react';
import { apiUrl, get, terminal, type Run } from './api';
import { moduleViews } from './moduleViews';
import { CopyValue, Raw, Table } from './components';

export type Execution = {
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
  const [observation, setObservation] = useState<string[] | null>(null);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setState('');
    setObservation(null);
    setError('');
    get<{ readiness: string; executionKind: string; capabilities?: Record<string, boolean> }>(
      `/dashboard/v1/experiments/${encodeURIComponent(id)}`,
      controller.signal,
      75000,
    )
      .then((value) => {
        if (!value || typeof value.readiness !== 'string' || !value.readiness)
          throw new Error('준비 상태 응답을 확인할 수 없습니다. 다시 확인하세요.');
        if (!controller.signal.aborted) {
          setState(value.readiness);
          if (value.capabilities)
            setObservation(
              Object.entries({
                jvmObservation: 'JVM 주기 관측',
                trace: 'Trace',
                jfr: 'JFR',
                runLogs: 'Run log',
              })
                .filter(([key]) => value.capabilities?.[key] === true)
                .map(([, label]) => label),
            );
        }
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
      {state && (
        <p>
          지원 관측:{' '}
          {observation === null
            ? '서버가 제공하지 않음'
            : observation.length
              ? observation.join(' · ')
              : '추가 수집 없음 (실행 결과의 worker 환경·CHECK·등록된 metric은 별도)'}
        </p>
      )}
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
  view = 'overview',
  onExecution,
  metrics,
}: {
  run: Run;
  features: Record<string, boolean>;
  pageSize: number;
  view?: string;
  onExecution?: (value: Execution | null) => void;
  metrics?: ReactNode;
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
          onExecution?.(value);
          setChecks(page);
          delay = 2000;
        }
      } catch (e) {
        if (!controller.signal.aborted) {
          setExecution(null);
          onExecution?.(null);
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
  }, [run.runId, run.status, features, after, revision, pageSize, onExecution]);
  const evidence = execution?.execution;
  const showMetrics =
    view === 'metrics' ||
    (view === 'overview' && (evidence?.definition.kind === 'IN_PROCESS' || !features.checksRead));
  return (
    <section
      id="worker-evidence"
      className={'evidence-workspace evidence-view-' + view}
      aria-label="실행 및 검증 증거"
    >
      {busy && <p role="status">실행 증거 확인 중…</p>}
      {error && (
        <div role="alert" className="error">
          {error} <button onClick={() => setRevision((n) => n + 1)}>증거 다시 조회</button>
        </div>
      )}
      <section
        className="panel evidence-inspector"
        hidden={!['overview', 'checks', 'environment', 'metrics', 'artifacts'].includes(view)}
      >
        <h2>실제 실행 환경</h2>
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
                  'Worker JVM',
                  evidence.worker?.environment.javaVersion
                    ? 'Java ' + String(evidence.worker.environment.javaVersion)
                    : '미기록',
                ],
                [
                  'Controller JVM',
                  run.runMetadata.environment.javaVersion
                    ? 'Java ' + String(run.runMetadata.environment.javaVersion)
                    : '미기록',
                ],
              ]}
            />
            <section className="inspector-card">
              <h2>증거 상태</h2>
              <p
                className={evidence.evidenceComplete ? 'evidence-complete' : 'evidence-incomplete'}
              >
                {evidence.evidenceComplete ? '증거 수집 완료' : '증거 미완료 · 확인된 부분만 표시'}
              </p>
              <small>종료 상태와 증거 완결성을 함께 확인합니다.</small>
            </section>
            <section className="inspector-card">
              <h2>재현 정보</h2>
              <CopyValue
                label="Git commit"
                value={
                  evidence.definition.kind === 'IN_PROCESS'
                    ? run.runMetadata.git.commit
                    : evidence.worker?.git.commit
                }
              />
              {evidence.worker && (
                <CopyValue label="배포 SHA-256" value={evidence.worker.artifactSha256} />
              )}
              {evidence.definition.kind === 'IN_PROCESS' && (
                <Raw
                  label="Controller JVM / 운영체제 / Git"
                  value={{ ...run.runMetadata.environment, git: run.runMetadata.git }}
                />
              )}
              {['LOCAL_JVM', 'CONTAINER'].includes(evidence.definition.kind) &&
                (evidence.worker ? (
                  <>
                    <Raw label="Worker JVM / 운영체제 / Git" value={evidence.worker} />
                  </>
                ) : (
                  <p>
                    Worker 환경은 아직 기록되지 않았습니다. 서버 환경으로 대신 표시하지 않습니다.
                  </p>
                ))}
            </section>
          </>
        )}
        <div className="inspector-card export-card">
          <h2>증거 내보내기</h2>
          {!terminal(run) && <small>실행 종료 후 내보내기를 제공합니다.</small>}
          {terminal(run) && !features.extendedExport && <small>확장 내보내기 미지원 · 기본 내보내기는 아티팩트 보기에서 제공합니다.</small>}
          {features.extendedExport && terminal(run) && (
            <a href={apiUrl(`/dashboard/v1/runs/${encodeURIComponent(run.runId)}/export`)}>
              실행·검증 포함 내보내기 (NDJSON)
            </a>
          )}
        </div>
      </section>
      <section
        className="panel checks-panel"
        hidden={!['overview', 'checks', 'metrics'].includes(view)}
      >
        {showMetrics
          ? metrics
          : features.checksRead && (
              <>
                <div className="section-heading">
                  <h2>검증 결과</h2>
                </div>
                {moduleViews[run.experimentType]?.introduction && (
                  <p className="measurement-scope">
                    {moduleViews[run.experimentType].introduction}
                  </p>
                )}
                {!terminal(run) && <p>실행 종료 후 검증 결과를 조회합니다.</p>}
                {checks &&
                  (!checks.available ? (
                    <p>이 실행에는 검증 정보가 기록되지 않았습니다.</p>
                  ) : (
                    <>
                      <p>
                        PASS는 기록된 기대값 충족을 뜻합니다. 의도한 이상 현상을 재현한 경우에도
                        PASS일 수 있습니다.
                      </p>
                      {checks.checks.length > 0 && (
                        <p className="check-summary">
                          현재 페이지 · {checks.checks.filter((c) => c.status === 'PASS').length}{' '}
                          PASS · {checks.checks.filter((c) => c.status === 'FAIL').length} FAIL
                        </p>
                      )}
                      <div className="worker-checks">
                        <Table
                          label="기대값과 관측값"
                          headers={['검증 / 설명', '판정', '기대값', '관측값']}
                          rows={checks.checks.map((c) => [
                            <>
                              {c.checkId}
                              <small>{c.description}</small>
                            </>,
                            <span
                              className={
                                'badge ' +
                                (c.status === 'PASS'
                                  ? 'succeeded'
                                  : c.status === 'FAIL'
                                    ? 'failed'
                                    : '')
                              }
                            >
                              {c.status}
                            </span>,
                            c.expected,
                            c.observed.length > 240 || c.observed.includes('\n') ? (
                              <Raw label="관측 상세 보기" value={c.observed} />
                            ) : (
                              c.observed
                            ),
                          ])}
                        />
                      </div>
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
        {!showMetrics && !features.checksRead && (
          <p>이 서버는 검증 결과 조회를 지원하지 않습니다.</p>
        )}
      </section>
    </section>
  );
}
