/** Unknown versions retain the existing API path. Only explicit true enables a feature. */
export function capabilities(value: unknown): { schemaVersion: 1; features: Record<string, boolean> } | null {
  if (!value || typeof value !== 'object' || !('schemaVersion' in value) || value.schemaVersion !== 1)
    return null;
  if (!('features' in value) || !value.features || typeof value.features !== 'object' || Array.isArray(value.features))
    return null;
  const features: Record<string, boolean> = {};
  for (const key of ['executionEvidence', 'checksRead', 'extendedExport', 'executionComparison', 'localJvm', 'cancellation'])
    features[key] = Object.prototype.hasOwnProperty.call(value.features, key)
      && (value.features as Record<string, unknown>)[key] === true;
  return { schemaVersion: 1, features };
}
