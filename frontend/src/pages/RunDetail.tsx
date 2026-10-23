import { RunFindings } from '../RunFindings';
import { useState } from 'react';
import { apiUrl, artifactReason, type Artifact, type MetricPage, type Run } from '../api';
import { Actions, Badge, Raw, Table, format } from '../components';
import { DeadLetters } from '../DeadLetters';
import { moduleViews } from '../moduleViews';
import { RecentRuns } from '../RecentRuns';
import { MetricCharts } from '../RunMetricCharts';
import { KpiStrip, PipelineFlowPanel } from '../RunOverview';
import { WorkerEvidence, type Execution } from '../WorkerEvidence';

export function RunDetail({
  run,
  artifacts,
  metrics,
  after,
  onAfter: setAfter,
  loading,
  features,
  options,
}: {
  run: Run;
  artifacts: Artifact[];
  metrics: MetricPage;
  after: number;
  onAfter: (after: number) => void;
  loading: boolean;
  features: Record<string, boolean>;
  options: { pageSize: number; artifactDownloadMaxBytes: number };
}) {
  const [selectedView, setDetailView] = useState('auto');
  const [execution, setExecution] = useState<Execution | null>(null);
  const detailView =
    selectedView === 'auto'
      ? execution?.execution?.definition.measurementScope === 'CHECK_RESULTS'
        ? 'checks'
        : 'overview'
      : selectedView;
  const DetailPanel = run && moduleViews[run.experimentType]?.detail;
  const metricContent = run && (
    <>
      <section className="detail-panel" hidden={!['overview', 'metrics'].includes(detailView)}>
        <MetricCharts run={run} page={metrics} pageSize={options.pageSize} />
        <details className="metric-source" open={detailView === 'overview'}>
          <summary id="run-metrics">원본 메트릭 요약</summary>
          {Object.keys(run.summary.counters).length + Object.keys(run.summary.gauges).length >
            0 && (
            <Table
              label="원본 metric 이름과 값"
              headers={['측정값', '종류', '값']}
              rows={[
                ...Object.entries(run.summary.counters).map(([k, v]) => [k, 'counter', format(v)]),
                ...Object.entries(run.summary.gauges).map(([k, v]) => [k, 'gauge', format(v)]),
              ]}
            />
          )}
          <Raw label="결과 상세" value={run.summary.details} />
        </details>
      </section>
      <section className="detail-panel" hidden={!['overview', 'metrics'].includes(detailView)}>
        <h2>관측 기록</h2>
        {metrics.metrics.length === 0 && after === -1 && metrics.nextAfter === null ? (
          <p>
            이 페이지에 측정값이 없습니다. 검증 실험은 위의 검증 결과와 실행·검증 내보내기를
            확인하세요.
          </p>
        ) : (
          <>
            <details className="metric-source" open={detailView === 'overview'}>
              <summary>원본 관측값</summary>
              <Table
                label="원본 관측 · 한 페이지씩 조회"
                headers={['순서 / 시각', '관측값']}
                rows={metrics.metrics.map((m) => [
                  <>
                    {m.sequence}
                    <small>{new Date(m.point.timestampEpochMs).toLocaleString()}</small>
                  </>,
                  <pre>{format(m.point)}</pre>,
                ])}
              />
            </details>
            <div className="actions">
              <button onClick={() => setAfter(-1)} disabled={loading}>
                첫 관측
              </button>
              {metrics.nextAfter !== null && (
                <button disabled={loading} onClick={() => setAfter(metrics.nextAfter!)}>
                  다음 관측
                </button>
              )}
            </div>
          </>
        )}
      </section>
    </>
  );
  return (
    <>
      <section className="run-strip" aria-label="선택한 실행">
        <Badge run={run} />
        <h2>{run.scenarioId}</h2>
        <span className="strip-field">
          <small>Run ID</small>
          <span className="mono" title={run.runId}>
            {run.runId}
          </span>
        </span>
        {execution?.execution && (
          <span className="strip-field">
            <small>실행 종류</small>
            <span className="mono">{execution.execution.definition.kind}</span>
          </span>
        )}
        {run.completedAtEpochMs && run.startedAtEpochMs ? (
          <span className="strip-field">
            <small>경과 시간</small>
            <span className="duration">
              {((run.completedAtEpochMs - run.startedAtEpochMs) / 1000).toFixed(2)} s
            </span>
          </span>
        ) : null}
        {run.startedAtEpochMs > 0 && (
          <span className="strip-field">
            <small>시작</small>
            <span className="mono">{new Date(run.startedAtEpochMs).toLocaleString()}</span>
          </span>
        )}
        {execution?.execution && (
          <span
            className={
              'badge evidence-badge ' +
              (execution.execution.evidenceComplete ? 'is-complete' : 'is-partial')
            }
          >
            {execution.execution.evidenceComplete ? '증거 완료' : '부분 증거'}
          </span>
        )}
      </section>
      <KpiStrip run={run} />
      <PipelineFlowPanel run={run} page={metrics} />
      <div className="result-tabs" aria-label="결과 보기">
        {[
          ['overview', '전체'],
          ['checks', '검증 결과'],
          ['metrics', '메트릭'],
          ['environment', '실행 환경'],
          ['artifacts', '아티팩트'],
          ...(run.experimentType === 'pipeline.excel-ingestion'
            ? [['dead-letters', 'Dead-letter']]
            : []),
        ].map(([id, title]) => (
          <button
            key={id}
            disabled={id === 'checks' && !features.checksRead}
            aria-pressed={detailView === id}
            onClick={() => setDetailView(id)}
          >
            {title}
          </button>
        ))}
      </div>
      <div className="run-actions" hidden={!['overview', 'artifacts'].includes(detailView)}>
        <Actions run={run} />
      </div>
      {detailView === 'overview' && <RunFindings runId={run.runId} />}
      {run.failureMessage && <p className="error">{run.failureMessage}</p>}
      <section className="hypothesis-panel" hidden={!['environment'].includes(detailView)}>
        <h2>가설</h2>
        <p>
          {run.runMetadata.parameters['scenario.hypothesis'] ?? '기록된 가설이 없습니다.'}
        </p>
      </section>
      <section hidden={detailView !== 'overview'}>
        {DetailPanel && <DetailPanel id={run.runId} status={run.status} />}
      </section>
      {(features.executionEvidence || features.checksRead) && (
        <WorkerEvidence
          view={detailView}
          onExecution={setExecution}
          metrics={metricContent}
          key={run.runId}
          run={run}
          features={features}
          pageSize={options.pageSize}
        />
      )}
      {(!(features.executionEvidence || features.checksRead) ||
        (detailView === 'overview' &&
          execution?.execution?.definition.kind !== 'IN_PROCESS')) &&
        metricContent}
      {detailView === 'dead-letters' && (
        <DeadLetters run={run} pageSize={options.pageSize} />
      )}
      <RecentRuns run={run} />
      <section
        className="detail-panel"
        hidden={!['overview', 'environment'].includes(detailView)}
      >
        <h2 id="run-environment">입력과 실행 환경</h2>
        <Raw label="파라미터와 시나리오 정의" value={run.runMetadata.parameters} />
        <Raw
          label="서버 JVM / 운영체제 / Git"
          value={{
            ...run.runMetadata.environment,
            git: run.runMetadata.git,
          }}
        />
      </section>
      <section
        className="detail-panel"
        hidden={!['overview', 'artifacts'].includes(detailView)}
      >
        <h2 id="run-artifacts">첨부 증거</h2>
        <p className="note">
          다운로드 시 서버가 크기와 SHA-256을 검증합니다. 오류는 새 탭에 표시됩니다.
        </p>
        {!artifacts.length ? (
          <p>등록된 첨부 증거가 없습니다.</p>
        ) : (
          <Table
            label="첨부 파일과 무결성"
            headers={['경로', '크기 / SHA-256', '다운로드']}
            rows={artifacts.map((a) => [
              a.relativePath,
              <>
                {a.byteSize} bytes<small>{a.sha256}</small>
              </>,
              artifactReason(
                run,
                a,
                options.artifactDownloadMaxBytes,
                features.workerReportDownload,
              ) ?? (
                <a
                  target="_blank"
                  rel="noopener"
                  href={apiUrl(
                    `/runs/${encodeURIComponent(run.runId)}/artifacts/${encodeURIComponent(a.artifactId)}/download`,
                  )}
                >
                  {a.artifactId.startsWith('worker_')
                    ? 'REPORT 다운로드 ↗'
                    : '다운로드 ↗'}
                </a>
              ),
            ])}
          />
        )}
      </section>
    </>
  );
}
