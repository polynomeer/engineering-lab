import { it, expect } from 'vitest';
import { parseStudies } from './StudyDetails';
const study = {experimentId:'demo',caseStudy:{title:'Demo',sourcePath:'docs/demo.md',provenance:'NEW-DESIGN',limitations:'Synthetic'},
  strategies:[{id:'one',title:'One',implementation:'One implementation'}],presets:[]};
it('distinguishes old versions from invalid catalogs and rejects mismatched preset experiments', () => {
  expect(parseStudies({schemaVersion:2})).toBeNull();
  expect(parseStudies({schemaVersion:1,studies:[study],patterns:[]})?.studies).toHaveLength(1);
  for (const value of [null, {schemaVersion:1}, {schemaVersion:1,studies:Array(257).fill(study),patterns:[]},
    {schemaVersion:1,studies:[{...study,presets:[{id:'p',strategyIds:['one'],sourcePath:'experiments/p.json',definitionJson:'{"schemaVersion":1,"experiment":"other"}',definitionSha256:'a'.repeat(64)}]}],patterns:[]}])
    expect(() => parseStudies(value)).toThrow();
});
