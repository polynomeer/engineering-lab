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
  'career.mcp-platform.architecture': {
    introduction: '실제 계약 코드와 고의 위반 예제에 같은 ArchUnit 규칙을 적용합니다. VIOLATION은 규칙별 FAIL과 실패 Run을 남기는 데모입니다. FACT 기반 규칙과 NEW-DESIGN 예제를 구분해 확인하세요.',
  },
  'career.mcp-platform.validation': {
    introduction: 'Bean Validation → DTO 매핑 → 도메인 규칙을 검증합니다. 고정 시계(2026-01-01 UTC)와 합성 입력을 사용합니다. 예상한 입력 거절도 PASS이며, 후속 단계 미실행 여부를 별도로 기록합니다. DB나 업무 성능은 측정하지 않습니다.',
  },
  'career.mcp-platform.sequence': {
    introduction:
      '별도 Java 21 worker와 임시 MySQL에서 실행합니다. BEFORE는 겹침 재현용 50ms 지연이 있어 실행 시간을 업무 성능 개선율로 해석하지 마세요. ATOMIC_UPDATE와 SELECT_FOR_UPDATE는 겹침 방지를 검증합니다.',
  },
  'pipeline.excel-ingestion': { input: ExcelUpload, detail: ExcelProgress },
};
