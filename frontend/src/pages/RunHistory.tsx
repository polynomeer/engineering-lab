import type { Descriptor } from '../api';
import { Badge, Table } from '../components';
import type { RunPage } from '../useRouteData';

const runLink = (id: string) => '/app/runs/' + encodeURIComponent(id);

export function RunHistory({
  query,
  catalog,
  page,
  loading,
  error,
}: {
  query: URLSearchParams;
  catalog: Descriptor[];
  page: RunPage;
  loading: boolean;
  error: string;
}) {
  const filtered = ['experiment', 'scenario', 'status'].some((key) => query.get(key));
  const latest = new URLSearchParams([...query].filter(([key, value]) => key !== 'before' && value));
  return (
    <>
      <div className="history-toolbar">
        <form action="/app/runs">
          <label>
            실험 필터
            <select name="experiment" defaultValue={query.get('experiment') ?? ''}>
              <option value="">전체 실험</option>
              {catalog.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.title}
                </option>
              ))}
            </select>
          </label>
          <label>
            시나리오 ID 시작
            <input name="scenario" maxLength={200} defaultValue={query.get('scenario') ?? ''} placeholder="예: sweep-capacity" />
          </label>
          {/* Explicit label: a wrapping label would absorb the option texts into the name. */}
          <div className="history-field">
            <label htmlFor="history-status">상태</label>
            <select id="history-status" name="status" defaultValue={query.get('status') ?? ''}>
              <option value="">전체 상태</option>
              <option value="QUEUED">대기</option>
              <option value="RUNNING">실행 중</option>
              <option value="SUCCEEDED">성공</option>
              <option value="FAILED">실패</option>
              <option value="INTERRUPTED">중단</option>
            </select>
          </div>
          <button type="submit">기록 조회</button>
        </form>
      </div>
      <p className="note">입력과 환경을 확인하고, 같은 조건의 실행을 비교하세요.</p>
      <Table
        label="저장된 실행 · 최신순"
        headers={['시나리오 / Run ID', '실험', '상태', '생성 시각']}
        rows={page.runs.map((r) => [
            <>
              <a href={runLink(r.runId)}>{r.scenarioId}</a>
              <small>{r.runId}</small>
            </>,
            r.experimentType,
            <Badge run={r} />,
            new Date(r.createdAtEpochMs).toLocaleString(),
          ])}
      />
      {!loading && !error && !page.runs.length && (
        <p>
          {filtered
            ? '조건에 맞는 실행이 없습니다. 필터를 줄여 보세요.'
            : '이 페이지에 저장된 실행이 없습니다. 실험 실행 화면이나 CLI에서 실험을 실행하세요.'}
        </p>
      )}
      <div className="actions">
        <a
          href={'/app/runs' + (latest.size ? '?' + latest : '')}
        >
          최신 실행
        </a>
        {page.nextBefore !== null && (
          <a
            href={
              '/app/runs?' +
              new URLSearchParams({
                ...Object.fromEntries(query),
                before: String(page.nextBefore),
              })
            }
          >
            이전 실행
          </a>
        )}
      </div>
    </>
  );
}
