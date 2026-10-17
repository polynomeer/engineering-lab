import React, { useEffect, useState } from 'react';
import { ApiError, get, type Descriptor, type Module } from './api';
import { discover, parsePatternCatalog, type PatternCatalog } from './patternCatalog';

export function PatternExplorer({ catalog, modules, query, refresh }: {
  catalog: Descriptor[]; modules: Module[]; query: URLSearchParams; refresh: number;
}) {
  const [metadata, setMetadata] = useState<PatternCatalog | null>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'legacy' | 'unsupported' | 'error'>('loading');
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setState('loading'); setMetadata(null);
    get<unknown>('/dashboard/v1/pattern-catalog', controller.signal).then(value => {
      if (controller.signal.aborted) return;
      const parsed = parsePatternCatalog(value);
      setMetadata(parsed); setState(parsed ? 'ready' : 'unsupported');
    }).catch(e => {
      if (!controller.signal.aborted) {
        if (e instanceof ApiError && e.status === 404) setState('legacy');
        else { setError(String(e.message)); setState('error'); }
      }
    });
    return () => controller.abort();
  }, [retry, refresh]);
  const entries = discover(catalog, modules, metadata);
  const category = query.get('category') ?? '', pattern = query.get('pattern') ?? '', source = query.get('source') ?? '';
  const sources = [...new Map(entries.map(e => [e.sourceModuleId, e.sourceTitle])).entries()].filter(([id]) => id);
  const visible = entries.filter(e => (!source || e.sourceModuleId === source) && (!metadata ||
    ((!category || (category === 'unclassified' ? !e.classification : e.classification?.categoryId === category))
    && (!pattern || e.classification?.patternIds.includes(pattern)))));
  const selectedPattern = metadata?.patterns.find(p => p.id === pattern);
  const unknown = !!metadata && ((category && category !== 'unclassified' && !metadata.categories.some(c => c.id === category))
    || (pattern && !metadata.patterns.some(p => p.id === pattern)));
  return <section className="pattern-explorer" aria-label="패턴 탐색">
    <p>반복되는 기술 문제로 실험을 찾고, 프로젝트 출처와 함께 살펴보세요.</p>
    {state === 'loading' && <p role="status">패턴 분류를 불러오는 중…</p>}
    {(state === 'legacy' || state === 'unsupported') && <p className="note" role="status">
      {state === 'legacy' ? '이 서버는 패턴 API를 제공하지 않습니다.' : '이 서버의 패턴 API 버전은 지원하지 않습니다.'}
      {' '}모듈별 탐색을 제공합니다. 분야·패턴 필터는 적용하지 않습니다.
    </p>}
    {state === 'error' && <div role="alert" className="error">패턴 분류 조회 실패: {error}
      <button onClick={() => setRetry(n => n + 1)}>분류 다시 시도</button>
      <p>아래 모듈 목록에서 실험을 열 수 있습니다. 분야·패턴 필터는 적용하지 않습니다.</p>
    </div>}
    <form action="/app/catalog" className="pattern-filters">
      <label>분야<select name="category" defaultValue={category} disabled={!metadata} key={'category-' + state}>
        <option value="">모든 분야</option>
        {metadata?.categories.map(c => <option key={c.id} value={c.id}>{c.title}</option>)}
        <option value="unclassified">미분류</option>
        {unknown && category && !metadata?.categories.some(c => c.id === category) && category !== 'unclassified' && <option value={category}>알 수 없는 분야</option>}
      </select></label>
      <label>패턴<select name="pattern" defaultValue={pattern} disabled={!metadata} key={'pattern-' + state}>
        <option value="">모든 패턴</option>
        {metadata?.patterns.map(p => <option key={p.id} value={p.id}>{p.title}</option>)}
        {unknown && pattern && !metadata?.patterns.some(p => p.id === pattern) && <option value={pattern}>알 수 없는 패턴</option>}
      </select></label>
      <label>프로젝트 출처<select name="source" defaultValue={source} key={sources.map(([id]) => id).join(",")}>
        <option value="">모든 출처</option>
        {sources.map(([id, title]) => <option key={id} value={id}>{title}</option>)}
        {source && !sources.some(([id]) => id === source) && <option value={source}>알 수 없는 출처</option>}
      </select></label>
      <button type="submit">필터 적용</button><a className="secondary-action" href="/app/catalog">필터 초기화</a>
    </form>
    {metadata && <p className="note">분야는 대표 분야 기준입니다. 분류는 현재 기준이며, 실행 당시 기록이나 실행 준비 상태를 뜻하지 않습니다.</p>}
    {selectedPattern && <section className="pattern-definition" aria-label="선택한 패턴 설명">
      <h2>{selectedPattern.title}</h2><dl>
        <dt>문제</dt><dd>{selectedPattern.problem}</dd>
        <dt>검증할 성질</dt><dd>{selectedPattern.verification}</dd>
        <dt>적용 한계</dt><dd>{selectedPattern.limitations}</dd>
      </dl>
    </section>}
    {unknown && <p role="alert">알 수 없는 분야 또는 패턴입니다. 필터를 초기화하거나 다시 선택하세요.</p>}
    <p role="status">{visible.length}개 실험 · 등록된 전체 {entries.length}개</p>
    {visible.length === 0 && <p>조건에 맞는 실험이 없습니다. 필터를 초기화하거나 서버의 실험 등록을 확인하세요.</p>}
    <div className="pattern-results">
      {visible.map(e => <article key={e.experiment.id} className="pattern-result">
        <p className="eyebrow">{e.sourceTitle}{e.sourceCollection ? ' / ' + e.sourceCollection : ''}</p>
        <h2>{e.experiment.title}</h2><code>{e.experiment.id}</code>
        {metadata && <><p>{metadata.categories.find(c => c.id === e.classification?.categoryId)?.title ?? '미분류'}</p>
          <ul aria-label={e.experiment.title + ' 패턴'}>{e.classification?.patternIds.map(id => <li key={id}>
            <a href={'/app/catalog?pattern=' + encodeURIComponent(id)}>{metadata.patterns.find(p => p.id === id)?.title}</a>
          </li>)}</ul></>}
        <div className="actions">
          <a href={'/app/experiments?experiment=' + encodeURIComponent(e.experiment.id)}>실험 설정</a>
          <a href={'/app/runs?experiment=' + encodeURIComponent(e.experiment.id)}>실행 기록</a>
        </div>
      </article>)}
    </div>
  </section>;
}
