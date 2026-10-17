import React, { useEffect, useState } from 'react';
import { ApiError, get } from './api';
import { download } from './execution';
export type Study = {
  experimentId: string;
  caseStudy: { title: string; sourcePath: string; provenance: string; limitations: string };
  strategies: { id: string; title: string; implementation: string }[];
  presets: { id: string; strategyIds: string[]; sourcePath: string; definitionJson: string; definitionSha256: string }[];
};
export type StudyCatalog = { schemaVersion: 1; studies: Study[]; patterns: {
  patternId: string; semanticVersion: string; status: 'ACTIVE' | 'DEPRECATED'; replacementPatternId: string | null;
}[] };
const record = (x: unknown): x is Record<string, any> => !!x && typeof x === 'object' && !Array.isArray(x);
const text = (x: unknown, max = 2000): x is string => typeof x === 'string' && x.length > 0 && x.length <= max;
const list = (x: unknown, max: number): x is any[] => Array.isArray(x) && x.length <= max;
export function parseStudies(value: unknown): StudyCatalog | null {
  if (!record(value) || !Number.isInteger(value.schemaVersion)) throw new Error('사례 응답이 올바르지 않습니다.');
  if (value.schemaVersion !== 1) return null;
  if (!list(value.studies,256) || !list(value.patterns,1024) || !value.patterns.every(p => record(p)
    && text(p.patternId,100) && text(p.semanticVersion,32) && ['ACTIVE','DEPRECATED'].includes(p.status)
    && (p.replacementPatternId === null || text(p.replacementPatternId,100)))
    || !value.studies.every(s => record(s) && text(s.experimentId,100) && record(s.caseStudy)
      && ['title','sourcePath','provenance','limitations'].every(k => text(s.caseStudy[k]))
      && list(s.strategies,32) && s.strategies.every((t: any) => record(t) && text(t.id,100) && text(t.title) && text(t.implementation))
      && list(s.presets,64) && s.presets.every((p: any) => {
        if (!record(p) || !text(p.id,100) || !list(p.strategyIds,32) || !p.strategyIds.length
          || !p.strategyIds.every((id: any) => s.strategies.some((t: any) => t.id === id))
          || !text(p.sourcePath,300) || !text(p.definitionJson,393216) || !text(p.definitionSha256,64)
          || !/^[a-f0-9]{64}$/.test(p.definitionSha256)) return false;
        try { const d = JSON.parse(p.definitionJson); return d.schemaVersion === 1 && d.experiment === s.experimentId; } catch { return false; }
      }))) throw new Error('사례·전략·프리셋 응답이 올바르지 않습니다.');
  return value as StudyCatalog;
}
export function useStudies(refresh: number) {
  const [catalog,setCatalog] = useState<StudyCatalog | null>(null), [message,setMessage] = useState('사례 설명을 불러오는 중…');
  const [retry,setRetry] = useState(0), [failed,setFailed] = useState(false);
  useEffect(() => {
    const controller = new AbortController(); setCatalog(null); setFailed(false); setMessage('사례 설명을 불러오는 중…');
    get<unknown>('/dashboard/v1/study-catalog',controller.signal).then(value => {
      if (controller.signal.aborted) return;
      const parsed = parseStudies(value); setCatalog(parsed); setMessage(parsed ? '' : '지원하지 않는 사례 API 버전입니다. 기존 탐색을 계속 사용할 수 있습니다.');
    }).catch(e => {
      if (controller.signal.aborted) return;
      const legacy = e instanceof ApiError && e.status === 404;
      setFailed(!legacy); setMessage(legacy ? '이 서버는 사례 API를 제공하지 않습니다. 기존 탐색을 계속 사용할 수 있습니다.' : '사례 조회 실패: ' + e.message);
    });
    return () => controller.abort();
  },[refresh,retry]);
  return {catalog,message,failed,retry:() => setRetry(n => n+1)};
}
export function StudyDetails({ study }: {study: Study}) {
  return <details className="study-details"><summary>전략·사례·시나리오</summary>
    <p>{study.caseStudy.provenance}</p><p>{study.caseStudy.limitations}</p>
    <p>근거 문서: <code>{study.caseStudy.sourcePath}</code></p>
    <h3>구현 전략</h3>
    <dl>{study.strategies.map(s => <React.Fragment key={s.id}><dt>{s.title}</dt><dd>{s.implementation}</dd></React.Fragment>)}</dl>
    <h3>시나리오 프리셋</h3>
    <p className="note">기존 정의를 저장합니다. 자동 실행하거나 현재 폼을 변경하지 않습니다. XLSX 정의는 원본 입력 파일도 필요합니다.</p>
    {study.presets.map(p => <details key={p.id}><summary>{p.id}</summary>
      <p>전략: {p.strategyIds.map(id => study.strategies.find(s => s.id === id)?.title).join(', ')}</p>
      <p>원본: <code>{p.sourcePath}</code></p><p>정의 SHA-256: <code>{p.definitionSha256}</code></p>
      <pre>{p.definitionJson}</pre>
      <button onClick={() => download('scenario.json',p.definitionJson,'application/json')}>정의 JSON 저장</button>
    </details>)}
  </details>;
}
