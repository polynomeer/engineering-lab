import { it, expect } from 'vitest';
import { parseAssessment } from './ComparisonAssessment';
const value = {schemaVersion:1,baselineId:'a',candidateId:'b',state:'INSUFFICIENT_EVIDENCE',baselineContract:null,candidateContract:null,conditions:[{field:'contract',status:'UNKNOWN'}],measures:[]};
it('keeps legacy evidence unknown and distinguishes unsupported from malformed API', () => {
  expect(parseAssessment(value)?.state).toBe('INSUFFICIENT_EVIDENCE');
  expect(parseAssessment({schemaVersion:2})).toBeNull();
  for (const v of [null, {}, {...value,state:'WINNER'}, {...value,conditions:Array(601).fill({field:'x',status:'MATCH'})},
    {...value,candidateContract:{schemaVersion:1}}, {...value,measures:[{role:'elapsed',baseline:null,candidate:null,baselineValue:Infinity,candidateValue:0,status:'UNKNOWN'}]}])
    expect(() => parseAssessment(v)).toThrow();
});
