import { ExecutionReadiness } from './WorkerEvidence';
import React, { useEffect, useRef, useState } from 'react';
import { loadSettings, saveSettings } from './settings';
import { moduleViews } from './moduleViews';
import type { Descriptor } from './api';
import {
  MAX_REPETITIONS,
  MAX_SWEEP_POINTS,
  MAX_WARMUP,
  parseSweepValues,
  submitSeries,
  submitSweep,
  sweepPointCount,
} from './series';
import {
  definition,
  initialDraft,
  presets,
  readSaved,
  submitRun,
  supportsForm,
  type Draft,
  type Saved,
} from './execution';
export function ExecutionPage({
  catalog,
  experiment,
  checkReadiness,
}: {
  catalog: Descriptor[];
  experiment: string | null;
  checkReadiness: boolean;
}) {
  const descriptor = catalog.find((d) => d.id === experiment);
  const Input = descriptor && moduleViews[descriptor.id]?.input;
  return (
    <>
      <p>실험을 선택하고 조건과 가설을 기록하세요. 실행 결과는 공통 저장소에 보관됩니다.</p>
      <form action="/app/experiments">
        <label>
          실험 선택
          <select name="experiment" key={experiment + ":" + catalog.map(d => d.id).join(",")} defaultValue={experiment ?? ''} required>
            <option value="" disabled>
              실험을 선택하세요
            </option>
            {catalog.map((d) => (
              <option key={d.id} value={d.id}>
                {d.title}
              </option>
            ))}
          </select>
        </label>
        <button type="submit">설정 열기</button>
      </form>
      {experiment && !descriptor && catalog.length > 0 && (
        <p role="alert">등록되지 않은 실험입니다.</p>
      )}
      {descriptor &&
        (Input ? (
          <Input />
        ) : supportsForm(descriptor) ? (
          checkReadiness ? (
            <ExecutionReadiness key={descriptor.id} id={descriptor.id}>
              <ExecutionForm key={descriptor.id} descriptor={descriptor} catalog={catalog} />
            </ExecutionReadiness>
          ) : (
            <ExecutionForm key={descriptor.id} descriptor={descriptor} catalog={catalog} />
          )
        ) : (
          <p>이 실험의 입력 화면은 이행 중입니다. 상단의 기존 실험 실행 화면을 이용하세요.</p>
        ))}
    </>
  );
}
function ExecutionForm({ descriptor, catalog }: { descriptor: Descriptor; catalog: Descriptor[] }) {
  const [draft, setDraft] = useState<Draft>(() => initialDraft(descriptor));
  const [saved, setSaved] = useState<Saved[]>([]);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const alert = useRef<HTMLDivElement>(null);
  const [runId, setRunId] = useState('');
  const [seriesId, setSeriesId] = useState('');
  const [repeat, setRepeat] = useState({ repetitions: 1, warmup: 0 });
  const [sweepId, setSweepId] = useState('');
  const [sweepRows, setSweepRows] = useState([
    { parameter: '', values: '' },
    { parameter: '', values: '' },
  ]);
  const sweepGrid = () => {
    const grid: Record<string, (string | number | boolean)[]> = {};
    for (const row of sweepRows) {
      if (!row.parameter || !row.values.trim()) continue;
      if (grid[row.parameter]) throw new Error(`${row.parameter}를 두 번 지정했습니다.`);
      grid[row.parameter] = parseSweepValues(row.values, descriptor.parameters[row.parameter]?.type ?? 'string');
    }
    return grid;
  };
  let sweepPreview = '';
  try {
    const grid = sweepGrid();
    if (Object.keys(grid).length) sweepPreview = `${sweepPointCount(grid)}개 점 × ${repeat.warmup + repeat.repetitions}회`;
  } catch (e) {
    sweepPreview = e instanceof Error ? e.message : String(e);
  }
  useEffect(() => {
    try {
      setSaved(readSaved(localStorage, catalog));
    } catch {
      setNotice('브라우저 설정을 읽을 수 없습니다. 저장 없이 실험을 실행할 수 있습니다.');
    }
  }, [catalog]);
  useEffect(() => {
    if (error) alert.current?.focus();
  }, [error]);
  function save() {
    try {
      const document = definition(descriptor, draft);
      // Re-read before writing to retain presets saved by another tab.
      const entries = [
        {
          experiment: descriptor.id,
          draft: { ...draft, parameters: document.parameters },
        },
        ...readSaved(localStorage, catalog).filter(
          (s) => s.experiment !== descriptor.id || s.draft.id !== draft.id,
        ),
      ].slice(0, 12);
      saveSettings({ ...loadSettings(), entries });
      setSaved(entries);
      setNotice('이 브라우저에 설정을 저장했습니다. 최근 12개를 보관합니다.');
      setError('');
    } catch (e) {
      setError(e instanceof Error ? e.message : '설정을 저장할 수 없습니다.');
    }
  }
  function restore(value: Draft) {
    setDraft(value);
    setError('');
    setNotice('설정을 복원했습니다. 실행 전 조건을 확인하세요.');
  }
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError('');
    setRunId('');
    setSeriesId('');
    setSweepId('');
    try {
      const grid = sweepGrid();
      if (Object.keys(grid).length) {
        if (sweepPointCount(grid) > MAX_SWEEP_POINTS)
          throw new Error(`스윕 점은 최대 ${MAX_SWEEP_POINTS}개입니다.`);
        setSweepId(await submitSweep(definition(descriptor, draft), grid, repeat.repetitions, repeat.warmup));
      } else if (repeat.repetitions > 1 || repeat.warmup > 0)
        setSeriesId(await submitSeries(definition(descriptor, draft), repeat.repetitions, repeat.warmup));
      else setRunId(await submitRun(definition(descriptor, draft)));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  return (
    <section aria-label="실험 실행 설정">
      <h2>{descriptor.title}</h2>
      {moduleViews[descriptor.id]?.introduction && (
        <p className="note">{moduleViews[descriptor.id].introduction}</p>
      )}
      <a href={'/app/runs?experiment=' + encodeURIComponent(descriptor.id)}>이 실험의 실행 기록</a>
      <div className="actions" aria-label="기본 프리셋">
        {(presets[descriptor.id] ?? []).map((p) => (
          <button key={p.id} disabled={busy} onClick={() => restore(p)}>
            {p.id}
          </button>
        ))}
      </div>
      <div role="status">{notice}</div>
      {error && (
        <div role="alert" tabIndex={-1} ref={alert} className="error">
          {error}{' '}
          <a href={'/app/runs?experiment=' + encodeURIComponent(descriptor.id)}>실행 기록 확인</a>
        </div>
      )}
      <form onSubmit={submit} className="execution-form">
        <fieldset disabled={busy}>
          <legend>실행 조건 · SINGLE_PASS</legend>
          <label>
            시나리오 ID
            <input
              required
              maxLength={200}
              pattern="[a-zA-Z0-9][a-zA-Z0-9._\-]*"
              value={draft.id}
              onChange={(e) => setDraft({ ...draft, id: e.target.value })}
            />
            <small>영문·숫자로 시작하고 점·밑줄·하이픈을 사용할 수 있습니다.</small>
          </label>
          <label>
            가설
            <textarea
              required
              maxLength={4000}
              value={draft.hypothesis}
              onChange={(e) => setDraft({ ...draft, hypothesis: e.target.value })}
            />
          </label>
          <div className="parameter-grid">
            {Object.entries(descriptor.parameters)
              .sort(([a], [b]) => a.localeCompare(b))
              .map(([key, spec]) => {
                const change = (value: string | boolean) =>
                  setDraft({
                    ...draft,
                    parameters: { ...draft.parameters, [key]: value },
                  });
                return (
                  <label key={key}>
                    {key}
                    {spec.unit ? ` (${spec.unit})` : ''}
                    {spec.type === 'boolean' ? (
                      <select
                        value={String(draft.parameters[key])}
                        onChange={(e) => change(e.target.value === 'true')}
                      >
                        <option value="false">false · 사용 안 함</option>
                        <option value="true">true · 사용</option>
                      </select>
                    ) : spec.type === 'integer' ? (
                      <input
                        type="number"
                        required
                        step={1}
                        min={spec.minimum ?? undefined}
                        max={spec.maximum ?? undefined}
                        value={String(draft.parameters[key] ?? '')}
                        onChange={(e) => change(e.target.value)}
                      />
                    ) : (
                      <select
                        value={String(draft.parameters[key])}
                        onChange={(e) => change(e.target.value)}
                      >
                        {spec.values.map((v) => (
                          <option key={v}>{v}</option>
                        ))}
                      </select>
                    )}
                    {spec.type === 'integer' && (
                      <small>
                        {spec.minimum}–{spec.maximum}
                      </small>
                    )}
                  </label>
                );
              })}
          </div>
          <fieldset className="repeat-fields">
            <legend>반복 측정</legend>
            <label>
              측정 반복 횟수
              <input
                type="number"
                min={1}
                max={MAX_REPETITIONS}
                step={1}
                required
                value={repeat.repetitions}
                onChange={(e) => setRepeat({ ...repeat, repetitions: Number(e.target.value) })}
              />
              <small>1이면 단일 실행입니다. 2 이상이면 같은 정의를 순서대로 반복합니다(최대 {MAX_REPETITIONS}).</small>
            </label>
            <label>
              warmup 횟수
              <input
                type="number"
                min={0}
                max={MAX_WARMUP}
                step={1}
                required
                value={repeat.warmup}
                onChange={(e) => setRepeat({ ...repeat, warmup: Number(e.target.value) })}
              />
              <small>통계에서 제외하는 앞쪽 실행입니다(최대 {MAX_WARMUP}).</small>
            </label>
          </fieldset>
          <fieldset className="repeat-fields sweep-fields">
            <legend>파라미터 스윕 (선택)</legend>
            {sweepRows.map((row, i) => (
              <div className="sweep-row" key={i}>
                {/* Explicit labels: a wrapping label would absorb the option texts into the name. */}
                <span className="sweep-field">
                  <label htmlFor={`sweep-parameter-${i}`}>스윕 파라미터 {i + 1}</label>
                  <select
                    id={`sweep-parameter-${i}`}
                    value={row.parameter}
                    onChange={(e) =>
                      setSweepRows(sweepRows.map((r, j) => (j === i ? { ...r, parameter: e.target.value } : r)))
                    }
                  >
                    <option value="">사용 안 함</option>
                    {Object.keys(descriptor.parameters)
                      .sort()
                      .map((key) => (
                        <option key={key}>{key}</option>
                      ))}
                  </select>
                </span>
                <span className="sweep-field">
                  <label htmlFor={`sweep-values-${i}`}>스윕 값 {i + 1}</label>
                  <input
                    id={`sweep-values-${i}`}
                    value={row.values}
                    placeholder="예: 1, 4, 16"
                    onChange={(e) =>
                      setSweepRows(sweepRows.map((r, j) => (j === i ? { ...r, values: e.target.value } : r)))
                    }
                  />
                </span>
              </div>
            ))}
            <small>
              값마다 위 반복 설정으로 묶음을 하나씩 실행합니다. 조합은 최대 {MAX_SWEEP_POINTS}개입니다.
              {sweepPreview && ` 현재: ${sweepPreview}`}
            </small>
          </fieldset>
          <div className="actions">
            <button type="submit">
              {busy
                ? '접수 확인 중…'
                : sweepPreview.includes('개 점')
                  ? `스윕 실행 (${sweepPreview})`
                  : repeat.repetitions > 1 || repeat.warmup > 0
                    ? `반복 측정 실행 (${repeat.warmup + repeat.repetitions}회)`
                  : '실험 실행'}
            </button>
            <button type="button" onClick={save}>
              설정 저장
            </button>
          </div>
        </fieldset>
      </form>
      {sweepId && (
        <p role="status">
          스윕이 접수되었습니다. 점은 하나씩 순서대로 실행합니다.{' '}
          <a href={'/app/sweeps/' + encodeURIComponent(sweepId)}>접수된 스윕 보기</a>
        </p>
      )}
      {seriesId && (
        <p role="status">
          반복 측정이 접수되었습니다. 반복은 하나씩 순서대로 실행합니다.{' '}
          <a href={'/app/series/' + encodeURIComponent(seriesId)}>접수된 반복 측정 보기</a>
        </p>
      )}
      {runId && (
        <p role="status">
          실행이 접수되었습니다.{' '}
          <a href={'/app/runs/' + encodeURIComponent(runId)}>접수된 실행 상세 보기</a>
        </p>
      )}
      <h2>저장한 설정</h2>
      <p className="note">
        현재 브라우저 주소에서 저장한 설정입니다. 기존 화면의 설정은 왼쪽 설정 이전에서 가져올 수
        있습니다.
      </p>
      <div className="actions">
        {saved
          .filter((s) => s.experiment === descriptor.id)
          .map((s) => (
            <button key={s.draft.id} disabled={busy} onClick={() => restore(s.draft)}>
              {s.draft.id} 복원
            </button>
          ))}
      </div>
    </section>
  );
}
