import { expect, test } from 'vitest';
import { capabilities } from './capabilities';

test('unknown, absent or malformed versions preserve legacy behavior', () => {
  for (const value of [null, {}, { schemaVersion: 2, features: {} }, { schemaVersion: 1 }, { schemaVersion: 1, features: [] }])
    expect(capabilities(value)).toBeNull();
});
test('only explicit supported feature values are enabled', () => {
  const result = capabilities({ schemaVersion: 1, features: { executionEvidence: true, localJvm: 'true', cancellation: 1, futureFeature: true } });
  expect(result?.features.executionEvidence).toBe(true);
  expect(result?.features.localJvm).toBe(false);
  expect(result?.features.checksRead).toBe(false);
  expect(result?.features.futureFeature).toBeUndefined();
});
