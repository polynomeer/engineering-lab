import type { Comparison } from '../api';
import { ComparisonAssessment } from '../ComparisonAssessment';
import { Table, format } from '../components';
import { comparisonCsv, download } from '../execution';
import { ComparisonOverview } from '../RunOverview';

const runLink = (id: string) => '/app/runs/' + encodeURIComponent(id);

export function Compare({ query, comparison }: { query: URLSearchParams; comparison: Comparison | null }) {
  return (
    <>
      <form action="/app/compare">
        <label>
          기준 Run ID
          <input
            name="baseline"
            required
            defaultValue={query.get('baseline') ?? ''}
          />
        </label>
        <label>
          비교 Run ID
          <input
            name="candidate"
            required
            defaultValue={query.get('candidate') ?? ''}
          />
        </label>
        <button type="submit">비교하기</button>
      </form>
      {comparison && (
        <>
          <ComparisonOverview comparison={comparison} />
          <ComparisonAssessment key={comparison.baseline.runId + ':' + comparison.candidate.runId} baseline={comparison.baseline.runId} candidate={comparison.candidate.runId} />
          <ul className="warning">
            {comparison.cautions.map((c) => (
              <li key={c}>{c}</li>
            ))}
          </ul>
          <div className="actions">
            <button
              onClick={() =>
                download(
                  'run-comparison.csv',
                  comparisonCsv(comparison),
                  'text/csv;charset=utf-8',
                )
              }
            >
              비교 요약 CSV
            </button>
            <button
              onClick={() =>
                download(
                  'run-comparison.json',
                  JSON.stringify(comparison, null, 2),
                  'application/json',
                )
              }
            >
              비교 JSON
            </button>
            <a href={runLink(comparison.baseline.runId)}>기준 실행 상세</a>
            <a href={runLink(comparison.candidate.runId)}>비교 실행 상세</a>
          </div>
          <Table
            label="실행 상태"
            headers={['항목', '기준', '비교']}
            rows={[
              ['상태', comparison.baseline.status, comparison.candidate.status],
              [
                '실패 사유',
                format(comparison.baseline.failureMessage),
                format(comparison.candidate.failureMessage),
              ],
            ]}
          />
          <Table
            label="측정값 · 누락은 0이 아닙니다"
            headers={['측정값 / 종류', '단위', '기준', '비교']}
            rows={comparison.values.map((v) => [
              `${v.name} / ${v.kind}`,
              v.unit,
              format(v.baseline),
              format(v.candidate),
            ])}
          />
          <Table
            label="기록된 조건의 차이"
            headers={['조건', '기준', '비교', '일치 여부']}
            rows={comparison.conditions.map((c) => [
              c.field,
              <pre>{format(c.baseline)}</pre>,
              <pre>{format(c.candidate)}</pre>,
              c.different ? '다름' : '같음',
            ])}
          />
          <p>
            단일 실행으로 성능 향상을 단정하지 않습니다. 원본 증거와 조건을 함께 보관하세요.
          </p>
        </>
      )}
    </>
  );
}
