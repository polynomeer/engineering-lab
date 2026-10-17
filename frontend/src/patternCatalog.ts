import type { Descriptor, Module } from './api';
export type Category = { id: string; title: string };
export type Pattern = Category & { categoryId: string; problem: string; verification: string; limitations: string };
export type Classification = { categoryId: string; patternIds: string[] };
export type PatternCatalog = {
  schemaVersion: 1;
  categories: Category[];
  patterns: Pattern[];
  experiments: { experimentId: string; sourceModuleId: string; sourceCollection: string; classification: Classification | null }[];
};
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);
const text = (v: unknown, max = 1000): v is string => typeof v === 'string' && v.length > 0 && v.length <= max;
const id = (v: unknown): v is string => text(v, 100) && /^[a-z][a-z0-9.-]*$/.test(v);
function list(v: unknown, max: number): unknown[] {
  if (!Array.isArray(v) || v.length > max) throw new Error('패턴 카탈로그 목록이 올바르지 않습니다.');
  return v;
}
function unique(values: string[]) {
  if (new Set(values).size !== values.length) throw new Error('패턴 카탈로그 ID가 중복되었습니다.');
}
/** Unknown versions can fall back; malformed v1 is a visible error, never an empty catalog. */
export function parsePatternCatalog(value: unknown): PatternCatalog | null {
  if (!object(value) || !Number.isInteger(value.schemaVersion)) throw new Error('패턴 카탈로그 응답이 올바르지 않습니다.');
  if (value.schemaVersion !== 1) return null;
  const categories = list(value.categories, 128), patterns = list(value.patterns, 1024), experiments = list(value.experiments, 4096);
  if (!categories.every(c => object(c) && id(c.id) && text(c.title, 200))) throw new Error('분야 정의가 올바르지 않습니다.');
  const categoryIds = new Set(categories.map(c => (c as Category).id));
  if (!patterns.every(p => object(p) && id(p.id) && id(p.categoryId) && categoryIds.has(p.categoryId) && text(p.title, 200)
    && text(p.problem) && text(p.verification) && text(p.limitations))) throw new Error('패턴 정의가 올바르지 않습니다.');
  const patternMap = new Map((patterns as Pattern[]).map(p => [p.id, p]));
  if (!experiments.every(e => {
    if (!object(e) || !id(e.experimentId) || !text(e.sourceModuleId, 200) || !text(e.sourceCollection, 200)) return false;
    const c = e.classification;
    if (c === null) return true;
    if (!object(c) || !id(c.categoryId) || !categoryIds.has(c.categoryId) || !Array.isArray(c.patternIds)
      || !c.patternIds.length || c.patternIds.length > 32) return false;
    unique(c.patternIds);
    return c.patternIds.every(p => typeof p === 'string' && patternMap.has(p))
      && c.patternIds.some(p => patternMap.get(p)?.categoryId === c.categoryId);
  })) throw new Error('실험 분류가 올바르지 않습니다.');
  unique((categories as Category[]).map(c => c.id));
  unique((patterns as Pattern[]).map(p => p.id));
  unique((experiments as PatternCatalog['experiments']).map(e => e.experimentId));
  return value as PatternCatalog;
}
export function discover(catalog: Descriptor[], modules: Module[], metadata: PatternCatalog | null) {
  const bindings = new Map(metadata?.experiments.map(e => [e.experimentId, e]) ?? []);
  const owners = new Map(modules.flatMap(m => m.experiments.map(e => [e.id, m.module] as const)));
  // /experiments remains authoritative, including unclassified or newly added providers.
  return catalog.map(experiment => {
    const binding = bindings.get(experiment.id), owner = owners.get(experiment.id);
    return { experiment, sourceModuleId: owner?.id ?? binding?.sourceModuleId ?? '',
      sourceTitle: owner?.title ?? binding?.sourceModuleId ?? '출처 미제공',
      sourceCollection: owner?.collection ?? binding?.sourceCollection ?? '',
      classification: binding?.classification ?? null };
  });
}
