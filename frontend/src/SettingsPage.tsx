import React, { useState, useRef, useEffect } from 'react';
import type { Descriptor } from './api';
import { Raw } from './components';
import { download } from './execution';
import {
  loadSettings,
  mergeSettings,
  parseTransfer,
  readLegacy,
  saveSettings,
  type Settings,
} from './settings';
export function SettingsPage({ catalog }: { catalog: Descriptor[] }) {
  const [preview, setPreview] = useState<Settings>(),
    [selected, setSelected] = useState<number[]>([]);
  const [replace, setReplace] = useState(false),
    [includeHistory, setIncludeHistory] = useState(true),
    [includeSelection, setIncludeSelection] = useState(true);
  const [message, setMessage] = useState(''),
    [error, setError] = useState('');
  const request = useRef(0),
    alert = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (error) alert.current?.focus();
  }, [error]);
  function attempt(action: () => void) {
    setError('');
    try {
      action();
    } catch (e) {
      setError((e as Error).message);
    }
  }
  function inspect(raw: string) {
    request.current++;
    setPreview(undefined);
    const parsed = parseTransfer(raw, catalog);
    setPreview(parsed);
    setSelected(parsed.entries.map((_, i) => i));
    setMessage('미리보기만 완료했습니다. 아직 저장하지 않았습니다.');
  }
  async function file(file?: File) {
    const ticket = ++request.current;
    setPreview(undefined);
    setError('');
    if (!file) return;
    if (file.size > 1048576) {
      setError('설정은 1 MiB 이하만 지원합니다.');
      return;
    }
    try {
      const raw = await file.text();
      if (ticket === request.current) inspect(raw);
    } catch (e) {
      setError((e as Error).message);
    }
  }
  return (
    <>
      <h2>설정 이전</h2>
      <p>
        같은 주소의 기존 설정을 읽거나, v0.3.0까지의 기존 화면에서 ‘설정 내보내기’로 받은 JSON을 선택하세요. 원본 key는
        그대로 둡니다. 파일은 서버에 전송하지 않습니다. 최대 1 MiB·100항목이며 미리보기 후
        저장합니다.
      </p>
      <div className="actions">
        <button
          disabled={!catalog.length}
          onClick={() =>
            attempt(() => inspect(JSON.stringify(readLegacy(localStorage, sessionStorage))))
          }
        >
          같은 주소의 기존 설정 읽기
        </button>
        <button
          onClick={() =>
            attempt(() =>
              download(
                'lab-settings.json',
                JSON.stringify(loadSettings(), null, 2),
                'application/json',
              ),
            )
          }
        >
          현재 설정 내보내기
        </button>
      </div>
      <label>
        설정 JSON
        <input
          type="file"
          accept=".json,application/json"
          disabled={!catalog.length}
          onChange={(e) => void file(e.target.files?.[0])}
        />
      </label>
      {error && (
        <p role="alert" tabIndex={-1} ref={alert} className="error">
          {error}
        </p>
      )}
      <p role="status">{message}</p>
      {preview && (
        <>
          <h3>가져올 설정 선택</h3>
          {preview.entries.map((entry, i) => (
            <label className="setting-choice" key={i}>
              <input
                type="checkbox"
                checked={selected.includes(i)}
                onChange={(e) =>
                  setSelected((old) =>
                    e.target.checked ? [...old, i] : old.filter((n) => n !== i),
                  )
                }
              />
              {entry.experiment} · {entry.draft.id}
            </label>
          ))}
          <label className="setting-choice">
            <input
              type="checkbox"
              checked={includeHistory}
              onChange={(e) => setIncludeHistory(e.target.checked)}
            />
            IO 이력 {preview.echoes.length + preview.legacyIo.length}개 포함
          </label>
          <label className="setting-choice">
            <input
              type="checkbox"
              checked={includeSelection}
              onChange={(e) => setIncludeSelection(e.target.checked)}
            />
            비교 대상·목록 필터 포함
          </label>
          <Raw label="가져올 원본 값 확인" value={preview} />
          <label>
            같은 실험·시나리오 ID의 충돌 처리
            <select value={String(replace)} onChange={(e) => setReplace(e.target.value === 'true')}>
              <option value="false">기존 값 유지</option>
              <option value="true">선택한 값으로 교체</option>
            </select>
          </label>
          <p>
            설정 최대 12개를 넘으면 저장하지 않습니다. IO 이력은 중복 제거 후 신규 단건 20개·이전
            비교 12개까지 보관합니다.
          </p>
          <button
            onClick={() =>
              attempt(() => {
                const incoming = {
                  ...preview,
                  entries: preview.entries.filter((_, i) => selected.includes(i)),
                  echoes: includeHistory ? preview.echoes : [],
                  legacyIo: includeHistory ? preview.legacyIo : [],
                  baseline: includeSelection ? preview.baseline : '',
                  candidate: includeSelection ? preview.candidate : '',
                  filter: includeSelection ? preview.filter : '',
                };
                const merged = mergeSettings(loadSettings(), incoming, replace);
                saveSettings(merged);
                setMessage(
                  `저장했습니다. 설정 ${merged.entries.length}개, IO 이력 ${merged.echoes.length + merged.legacyIo.length}개. 원본은 유지됩니다.`,
                );
                setPreview(undefined);
              })
            }
          >
            선택한 설정 가져오기
          </button>
        </>
      )}
      <p>
        <a href="/app/compare">저장한 비교 대상 열기</a> ·{' '}
        <a href="/app/tools/io-echo">IO 이력 보기</a>
      </p>
    </>
  );
}
