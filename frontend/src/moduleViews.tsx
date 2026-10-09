import type { ComponentType } from 'react';
import { ExcelProgress, ExcelUpload } from './LegacyTools';
// Module-specific adapters are isolated from the shared router and Run page.
export const moduleViews: Record<
  string,
  {
    input?: ComponentType;
    detail?: ComponentType<{ id: string; status: string }>;
    introduction?: string;
  }
> = {
  'career.mcp-platform.sequence': {
    introduction:
      '별도 Java 21 worker와 임시 MySQL에서 실행합니다. BEFORE는 겹침 재현용 50ms 지연이 있어 실행 시간을 업무 성능 개선율로 해석하지 마세요. ATOMIC_UPDATE와 SELECT_FOR_UPDATE는 겹침 방지를 검증합니다.',
  },
  'pipeline.excel-ingestion': { input: ExcelUpload, detail: ExcelProgress },
};
