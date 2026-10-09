import { ExecutionReadiness } from './WorkerEvidence';
import React, { useEffect, useRef, useState } from 'react';
import { loadSettings, saveSettings } from './settings';
import { moduleViews } from './moduleViews';
import type { Descriptor } from './api';
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
          <select name="experiment" defaultValue={experiment ?? ''} required>
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
    try {
      setRunId(await submitRun(definition(descriptor, draft)));
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
          <div className="actions">
            <button type="submit">{busy ? '접수 확인 중…' : '실험 실행'}</button>
            <button type="button" onClick={save}>
              설정 저장
            </button>
          </div>
        </fieldset>
      </form>
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
