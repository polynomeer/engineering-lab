import { describe, it, expect } from 'vitest';
import { parsePatternCatalog, discover } from './patternCatalog';
import { parseRoute, type Descriptor } from './api';
const payload = { schemaVersion: 1, categories: [{id:'data',title:'Data'}],
  patterns: [{id:'stream',title:'Stream',categoryId:'data',problem:'Large input',verification:'Bounded memory',limitations:'Buffers'}],
  experiments: [{experimentId:'demo',sourceModuleId:'module',sourceCollection:'examples',classification:{categoryId:'data',patternIds:['stream']}}] };
describe('pattern discovery boundary', () => {
  it('accepts v1, rejects malformed references and bounds, and distinguishes unsupported versions', () => {
    expect(parsePatternCatalog(payload)).toEqual(payload);
    expect(parsePatternCatalog({schemaVersion:2})).toBeNull();
    for (const value of [null, {}, {...payload,patterns:[{...payload.patterns[0],categoryId:['data']}]}, {...payload,categories:[]}, {...payload,patterns:[...payload.patterns,...payload.patterns]},
      {...payload,experiments:[{...payload.experiments[0],classification:{categoryId:'data',patternIds:['missing']}}]},
      {...payload,categories:Array(129).fill(payload.categories[0])}]) expect(() => parsePatternCatalog(value)).toThrow();
  });
  it('preserves unclassified descriptors and never invents experiments from stale metadata', () => {
    const catalog = [{id:'new',title:'New'}] as Descriptor[];
    expect(discover(catalog, [], parsePatternCatalog(payload))).toEqual([
      {experiment:catalog[0],sourceModuleId:'',sourceTitle:'출처 미제공',sourceCollection:'',classification:null}]);
    expect(discover(catalog, [{module:{id:'owner',title:'Owner',collection:'custom'},experiments:catalog}], null)[0].sourceTitle).toBe('Owner');
  });
  it('adds a discovery route without changing existing experiment and Run URLs', () => {
    expect(parseRoute('/app/catalog','?pattern=stream').kind).toBe('catalog');
    expect(parseRoute('/app/experiments/demo','').query.get('experiment')).toBe('demo');
    expect(parseRoute('/app/experiments','?experiment=demo').kind).toBe('execute');
    expect(parseRoute('/app/runs/old-run','').kind).toBe('detail');
  });
});
