import { expect, it } from 'vitest';
import { validateWorkbook } from './LegacyTools';
it('rejects empty, wrong extension and oversize uploads before submitting', () => {
  expect(() => validateWorkbook(undefined)).toThrow();
  expect(() => validateWorkbook({ name: 'input.xlsx', size: 0 })).toThrow();
  expect(() => validateWorkbook({ name: 'input.csv', size: 10 })).toThrow();
  expect(() => validateWorkbook({ name: 'input.xlsx', size: 50 * 1024 * 1024 + 1 })).toThrow(
    '50 MiB',
  );
  expect(() => validateWorkbook({ name: 'input.XLSX', size: 50 * 1024 * 1024 })).not.toThrow();
});
