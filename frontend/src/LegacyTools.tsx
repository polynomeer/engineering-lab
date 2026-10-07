import React, { useEffect, useRef, useState } from 'react';
import { loadSettings, saveSettings, type Echo, type LegacyEcho } from './settings';
import { apiUrl, get } from './api';
import { Raw, Table } from './components';
import { csv, download } from './execution';
export function validateWorkbook(file: Pick<File, 'name' | 'size'> | undefined) {
  if (!file || !/\.xlsx$/i.test(file.name) || file.size === 0)
    throw new Error('비어 있지 않은 .xlsx 파일을 선택하세요.');
  if (file.size > 50 * 1024 * 1024) throw new Error('파일은 최대 50 MiB입니다.');
}
export function ExcelUpload() {
  const [file, setFile] = useState<File>();
  const [mode, setMode] = useState('PIPELINE');
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [id, setId] = useState('');
  const lock = useRef(false);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (lock.current) return;
    try {
      validateWorkbook(file);
    } catch (e) {
      setError(String((e as Error).message));
      return;
    }
    lock.current = true;
    setBusy(true);
    setError('');
    setId('');
    try {
      const body = new FormData();
      body.append('file', file!);
      body.append('mode', mode);
      const response = await fetch(apiUrl('/ingest/excel'), {
        method: 'POST',
        body,
        signal: AbortSignal.timeout(60000),
      });
      if (response.status >= 400 && response.status < 500 && response.status !== 408) {
        setError(`업로드가 거절되었습니다 (${response.status}). 파일과 서버 제한을 확인하세요.`);
        return;
      }
      if (response.status !== 202) throw new Error();
      const result = await response.json();
      if (typeof result.jobId !== 'string' || !result.jobId) throw new Error();
      setId(result.jobId);
    } catch {
      setError(
        '접수 여부를 확인할 수 없습니다. 실행 기록을 확인한 뒤 다시 제출하세요. 자동 재시도하지 않습니다.',
      );
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  return (
    <section>
      <h2>Excel 업로드</h2>
      <p>
        첫 시트를 처리합니다. 최대 50 MiB이며 서버 설정이 더 작으면 서버 제한을 따릅니다. 큐·배치
        설정은 기존 업로드 API와 동일하게 서버 기본값을 사용합니다.
      </p>
      <a href="/app/runs?experiment=pipeline.excel-ingestion">Excel 실행 기록</a>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <form onSubmit={submit}>
        <label>
          XLSX 파일
          <input
            type="file"
            accept=".xlsx"
            required
            disabled={busy}
            onChange={(e) => setFile(e.target.files?.[0])}
          />
        </label>
        <label>
          실행 모드
          <select value={mode} disabled={busy} onChange={(e) => setMode(e.target.value)}>
            <option>PIPELINE</option>
            <option>SINGLE_THREAD</option>
          </select>
        </label>
        <button disabled={busy}>{busy ? '업로드 접수 중…' : '업로드 실행'}</button>
      </form>
      {id && (
        <p role="status">
          접수되었습니다. <a href={'/app/runs/' + encodeURIComponent(id)}>Excel 작업 상세 보기</a>
        </p>
      )}
      <a href="/app/tools/ingestion-db">현재 DB 조회</a>
    </section>
  );
}
type Job = {
  fileName: string;
  runMode: string;
  producedCount: number;
  insertedCount: number;
  validationErrorCount: number;
  errorSummary: unknown;
  latestProgress: unknown;
  oomExists: boolean;
  dbDeadlock: boolean;
  failureMessage: string | null;
};
export function ExcelProgress({ id, status }: { id: string; status: string }) {
  const [job, setJob] = useState<Job>(),
    [error, setError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    let pending = false;
    async function load() {
      if (pending || controller.signal.aborted) return;
      pending = true;
      try {
        setJob(await get<Job>('/ingest/jobs/' + encodeURIComponent(id), controller.signal));
        setError('');
      } catch (e) {
        if (!controller.signal.aborted) setError((e as Error).message);
      }
      pending = false;
      if (!controller.signal.aborted && !document.hidden && ['QUEUED', 'RUNNING'].includes(status))
        timer = setTimeout(load, 2000);
    }
    const visibility = () => {
      clearTimeout(timer);
      if (!document.hidden) void load();
    };
    document.addEventListener('visibilitychange', visibility);
    void load();
    return () => {
      document.removeEventListener('visibilitychange', visibility);
      controller.abort();
      clearTimeout(timer);
    };
  }, [id, status]);
  return (
    <section>
      <h2>Excel 처리 현황</h2>
      {error && <p role="alert">{error}</p>}
      {job && (
        <>
          <Table
            label="행 집계"
            headers={['파일 / 모드', '읽은 행', '저장한 행', '검증 오류']}
            rows={[
              [
                `${job.fileName} / ${job.runMode}`,
                job.producedCount,
                job.insertedCount,
                job.validationErrorCount,
              ],
            ]}
          />
          <Raw label="검증 오류 집계" value={job.errorSummary} />
          <Raw label="큐·배치·처리 진행" value={job.latestProgress} />
          <Raw
            label="실패 진단"
            value={{ failure: job.failureMessage, oom: job.oomExists, dbDeadlock: job.dbDeadlock }}
          />
        </>
      )}
    </section>
  );
}
export function DatabaseTool() {
  const [limit, setLimit] = useState('200'),
    [data, setData] = useState<{
      totalRows: number;
      rows: { id: number; col1: string; col2: string; col3: number }[];
    }>();
  const [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  async function load(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const [count, rows] = await Promise.all([
        get<{ totalRows: number }>('/ingest/db/count'),
        get<{ rows: NonNullable<typeof data>['rows'] }>('/ingest/db/rows?limit=' + Number(limit)),
      ]);
      setData({ ...count, ...rows });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <h2>현재 ingestion DB</h2>
      <p>
        모든 작업이 공유하는 현재 테이블입니다. 특정 Run의 결과나 저장된 증거가 아닙니다. 전체
        건수와 행 조회 시점은 다를 수 있습니다.
      </p>
      <form onSubmit={load}>
        <label>
          최신 행 수
          <input
            type="number"
            required
            min={1}
            max={2000}
            value={limit}
            onChange={(e) => setLimit(e.target.value)}
          />
        </label>
        <button disabled={busy}>DB 조회</button>
      </form>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {data && (
        <>
          <p role="status">현재 전체 {data.totalRows}행</p>
          <Table
            label="현재 DB 최신 행"
            headers={['ID', 'col1', 'col2', 'col3']}
            rows={data.rows.map((r) => [r.id, r.col1, r.col2, r.col3])}
          />
        </>
      )}
    </>
  );
}
export function IoEchoTool() {
  const [message, setMessage] = useState(
    new URLSearchParams(location.search).get('msg')?.slice(0, 1000) ?? 'hello',
  );
  const [delay, setDelay] = useState('100'),
    [pinDelay, setPinDelay] = useState('100'),
    [pinning, setPinning] = useState(false);
  const [history, setHistory] = useState<Echo[]>([]),
    [busy, setBusy] = useState(false),
    [error, setError] = useState('');
  const [legacyHistory, setLegacyHistory] = useState<LegacyEcho[]>([]);
  useEffect(() => {
    try {
      const saved = loadSettings();
      setHistory(saved.echoes.slice(0, 20));
      setLegacyHistory(saved.legacyIo.slice(0, 12));
    } catch {
      setError('브라우저 이력을 읽을 수 없습니다. 저장 없이 요청할 수 있습니다.');
    }
  }, []);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  async function run(mode: string) {
    if (controller.current) return;
    if (
      !message.trim() ||
      message.length > 1000 ||
      ![delay, pinDelay].every(
        (v) => v.trim() && Number.isInteger(Number(v)) && Number(v) >= 0 && Number(v) <= 5000,
      )
    ) {
      setError('메시지 1–1000자, 지연 0–5000ms를 입력하세요.');
      return;
    }
    const abort = new AbortController();
    controller.current = abort;
    setBusy(true);
    setError('');
    try {
      const started = performance.now();
      const query = new URLSearchParams({
        msg: message,
        delayMs: delay,
        pinning: String(pinning),
        pinDelayMs: pinDelay,
      });
      const response = await fetch(apiUrl('/lab/io/' + mode + '/echo?' + query), {
        signal: AbortSignal.any([abort.signal, AbortSignal.timeout(15000)]),
      });
      if (!response.ok || !response.headers.get('content-type')?.includes('text/plain'))
        throw new Error(`데모 요청 실패 (${response.status})`);
      const body = await response.text();
      const next = [
        {
          time: new Date().toISOString(),
          mode,
          elapsedMs: Math.round(performance.now() - started),
          response: body,
        },
        ...history,
      ].slice(0, 20);
      setHistory(next);
      try {
        saveSettings({ ...loadSettings(), echoes: next });
      } catch {
        setError(
          '응답은 받았지만 브라우저에 이력을 저장하지 못했습니다. CSV로 보관할 수 있습니다.',
        );
      }
    } catch (e) {
      if (!abort.signal.aborted) setError((e as Error).message);
    } finally {
      controller.current = null;
      if (!abort.signal.aborted) setBusy(false);
    }
  }
  return (
    <>
      <h2>IO 단건 데모</h2>
      {legacyHistory.length > 0 && (
        <Raw label="이전 IO 비교 이력 · 두 방식의 평균 왕복 시간" value={legacyHistory} />
      )}
      <p>
        Run을 만들지 않는 요청 데모입니다. 소요 시간은 브라우저 왕복 시간이며 실험 처리량과
        다릅니다. 이 주소의 브라우저에 최근 20개를 보관합니다. 저장이 차단되면 현재 탭에서만
        유지됩니다.
      </p>
      <form onSubmit={(e) => e.preventDefault()}>
        <label>
          메시지
          <input maxLength={1000} value={message} onChange={(e) => setMessage(e.target.value)} />
        </label>
        <label>
          지연 (ms)
          <input
            type="number"
            min={0}
            max={5000}
            value={delay}
            onChange={(e) => setDelay(e.target.value)}
          />
        </label>
        <label>
          Pin 지연 (ms)
          <input
            type="number"
            min={0}
            max={5000}
            value={pinDelay}
            onChange={(e) => setPinDelay(e.target.value)}
          />
        </label>
        <label>
          Pinning
          <select value={String(pinning)} onChange={(e) => setPinning(e.target.value === 'true')}>
            <option value="false">false</option>
            <option value="true">true</option>
          </select>
        </label>
        <button type="button" disabled={busy} onClick={() => void run('virtual-thread')}>
          Virtual thread 요청
        </button>
        <button type="button" disabled={busy} onClick={() => void run('reactive')}>
          Reactive 요청
        </button>
      </form>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <p role="status">{busy ? '응답 대기 중…' : `${history.length}개 응답`}</p>
      <button
        disabled={!history.length}
        onClick={() =>
          download(
            'io-echo.csv',
            csv([
              ['time', 'mode', 'browserElapsedMs', 'response'],
              ...history.map((h) => [h.time, h.mode, h.elapsedMs, h.response]),
            ]),
            'text/csv;charset=utf-8',
          )
        }
      >
        데모 CSV
      </button>
      <Table
        label="현재 탭의 IO 응답"
        headers={['시각 / 방식', '왕복 ms', '응답']}
        rows={history.map((h) => [`${h.time} / ${h.mode}`, h.elapsedMs, h.response])}
      />
    </>
  );
}
