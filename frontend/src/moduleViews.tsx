import type { ComponentType } from 'react';
import { ExcelProgress, ExcelUpload } from './LegacyTools';
// Module-specific adapters are isolated from the shared router and Run page.
export const moduleViews: Record<
  string,
  { input: ComponentType; detail: ComponentType<{ id: string; status: string }> }
> = {
  'pipeline.excel-ingestion': { input: ExcelUpload, detail: ExcelProgress },
};
