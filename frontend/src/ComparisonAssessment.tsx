import React, { useEffect, useState } from 'react';
import { ApiError, get } from './api';
import { download } from './execution';

type Match = 'MATCH' | 'MISMATCH' | 'UNKNOWN';
type Measure = { role: string; kind: 'counter' | 'gauge'; name: string; unit: string; scope: string; semantics: string; window: string };
type Contract = { schemaVersion: 1; experimentId: string; methodVersion: string; limitations: string;
  properties: { id: string; meaning: string; evidence: string }[]; measures: Measure[]; requiredParameters: string[] };
export type Assessment = { schemaVersion: 1; baselineId: string; candidateId: string;
  state: 'RECORDED_CONDITIONS_MATCH' | 'REVIEW_REQUIRED' | 'INSUFFICIENT_EVIDENCE';
  baselineContract: Contract | null; candidateContract: Contract | null;
  conditions: { field: string; status: Match }[];
  measures: { role: string; baseline: Measure | null; candidate: Measure | null;
    baselineValue: number | null; candidateValue: number | null; status: Match }[] };
const record = (v: unknown): v is Record<string, any> => !!v && typeof v === 'object' && !Array.isArray(v);
const text = (v: unknown, max = 1000): v is string => typeof v === 'string' && v.length > 0 && v.length <= max;
const list = (v: unknown, max: number): v is any[] => Array.isArray(v) && v.length <= max;
const match = (v: unknown) => ['MATCH', 'MISMATCH', 'UNKNOWN'].includes(String(v));
const measure = (v: unknown): boolean => record(v) && ['role', 'name', 'unit', 'scope', 'semantics', 'window'].every(k => text(v[k])) && ['counter', 'gauge'].includes(v.kind);
const contract = (v: unknown): boolean => v === null || (record(v) && v.schemaVersion === 1
  && ['experimentId', 'methodVersion', 'limitations'].every(k => text(v[k]))
  && list(v.properties,16) && v.properties.every(p => record(p) && ['id','meaning','evidence'].every(k => text(p[k])))
  && list(v.measures,32) && v.measures.every(measure) && list(v.requiredParameters,64) && v.requiredParameters.every(k => text(k,100)));
export function parseAssessment(v: unknown): Assessment | null {
  if (!record(v) || !Number.isInteger(v.schemaVersion)) throw new Error('측정 계약 응답이 올바르지 않습니다.');
  if (v.schemaVersion !== 1) return null;
  const number = (n: unknown) => n === null || (typeof n === 'number' && Number.isFinite(n));
  if (!text(v.baselineId,100) || !text(v.candidateId,100)
    || !['RECORDED_CONDITIONS_MATCH','REVIEW_REQUIRED','INSUFFICIENT_EVIDENCE'].includes(v.state)
    || !contract(v.baselineContract) || !contract(v.candidateContract)
    || !list(v.conditions,600) || !v.conditions.every(c => record(c) && text(c.field) && match(c.status))
    || !list(v.measures,64) || !v.measures.every(m => record(m) && text(m.role) && match(m.status)
      && (m.baseline === null || measure(m.baseline)) && (m.candidate === null || measure(m.candidate))
      && number(m.baselineValue) && number(m.candidateValue))) throw new Error('측정 계약 응답이 올바르지 않습니다.');
  return v as Assessment;
}
const labels = { MATCH: '일치', MISMATCH: '불일치', UNKNOWN: '판정 불가' };
const states = { RECORDED_CONDITIONS_MATCH: '기록된 조건 일치', REVIEW_REQUIRED: '조건 차이 검토 필요', INSUFFICIENT_EVIDENCE: '비교 근거 부족' };
export function ComparisonAssessment({baseline, candidate}: {baseline: string; candidate: string}) {
  const [data,setData] = useState<Assessment | null>(null), [message,setMessage] = useState('측정 계약을 불러오는 중…');
  const [failed,setFailed] = useState(false), [retry,setRetry] = useState(0);
  useEffect(() => {
    const abort = new AbortController(); setData(null); setFailed(false); setMessage('측정 계약을 불러오는 중…');
    get<unknown>(`/dashboard/v1/comparison-assessment?${new URLSearchParams({baseline,candidate})}`,abort.signal).then(value => {
      if (abort.signal.aborted) return;
      const result = parseAssessment(value);
      if (result && (result.baselineId !== baseline || result.candidateId !== candidate)) throw new Error('선택한 실행과 응답이 다릅니다.');
      setData(result); setMessage(result ? '' : '지원하지 않는 측정 API 버전입니다. 기존 비교를 계속 사용할 수 있습니다.');
    }).catch(e => {
      if (abort.signal.aborted) return;
      const missing = e instanceof ApiError && e.status === 404;
      setFailed(!missing); setMessage(missing ? '측정 API 또는 실행을 찾을 수 없습니다. 기존 비교를 계속 사용할 수 있습니다.' : '측정 계약 조회 실패: ' + e.message);
    });
    return () => abort.abort();
  },[baseline,candidate,retry]);
  return <section className="measurement-assessment" aria-label="측정 계약 비교">
    <h3>측정 계약 비교</h3>
    <p className="note">실행 시 저장한 계약으로 단위·범위·측정 구간을 대조합니다. 조건 일치는 성능 우위나 검증 성질의 통과를 의미하지 않습니다.</p>
    {message && <p role={failed ? 'alert' : 'status'}>{message}</p>}
    {failed && <button onClick={() => setRetry(n => n+1)}>측정 계약 다시 조회</button>}
    {data && <>
      <p className="warning">{states[data.state]} · 단일 실행은 통계적 근거가 아닙니다.</p>
      {(!data.baselineContract || !data.candidateContract) && <p>실행 당시 계약이 없는 기록은 판정 불가입니다. 현재 카탈로그로 과거 의미를 추정하지 않습니다.</p>}
      <details><summary>조건별 판정 ({data.conditions.filter(c => c.status !== 'MATCH').length}개 확인 필요)</summary>
        <ul>{data.conditions.map(c => <li key={c.field}><code>{c.field}</code>: {labels[c.status]}</li>)}</ul>
      </details>
      <div className="measurement-sides">
        {([['기준', data.baselineContract, 'baseline'], ['후보', data.candidateContract, 'candidate']] as const).map(([title, c, side]) => <div key={side}>
          <h4>{title} 실행의 측정 범위</h4>
          {!c ? <p>기록 없음</p> : <>
            <p>{c.experimentId} · 방법 버전 {c.methodVersion}</p><p>{c.limitations}</p>
            {c.measures.length === 0 && <p>성능 비교 지표를 선언하지 않은 CHECK 중심 검증입니다. 실제 CHECK·artifact는 실행 상세에서 확인하세요.</p>}
            {data.measures.map(m => {
              const d = m[side]; if (!d) return null;
              return <dl key={m.role}><dt>{m.role} · {labels[m.status]}</dt>
                <dd>{m[side === 'baseline' ? 'baselineValue' : 'candidateValue'] ?? '미기록'} {d.unit}</dd>
                <dd>{d.name} ({d.kind}) · {d.scope} · {d.semantics}</dd><dd>{d.window}</dd></dl>;
            })}
            <details><summary>공통 검증 성질 (목표, 통과 판정 아님)</summary>
              {c.properties.map(p => <dl key={p.id}><dt>{p.id}: {p.meaning}</dt><dd>{p.evidence}</dd></dl>)}
            </details>
          </>}
        </div>)}
      </div>
      <button onClick={() => download('comparison-assessment.json',JSON.stringify(data,null,2),'application/json')}>측정 계약 비교 JSON</button>
    </>}
    <p className="note">아래 기존 요약 표의 단위는 서버의 현재 metric 카탈로그 참고값입니다. 실행 당시 단위·범위는 위 계약을 우선 확인하세요.</p>
  </section>;
}
