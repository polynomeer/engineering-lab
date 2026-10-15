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
  'career.creator-studio.session-rotation': {
    introduction: '실제 Redis에서 opaque token을 교체합니다. BEFORE의 PASS는 옛 토큰이 남는 문제 재현입니다. AFTER는 재사용·기기 불일치를 거절하고 CONCURRENT는 같은 refresh token의 두 요청 중 하나만 성공하는지 확인합니다. 개선안은 당시 배포된 기능이 아닌 제안 설계의 재구현이며 JWT 서명·탈취 탐지·Redis Cluster는 검증하지 않습니다.',
  },
  'career.creator-studio.mapping-integrity': {
    introduction: '실제 MySQL에서 가입과 매핑 변경을 분리합니다. BEFORE는 확정된 장애 원인이 아닌 덮어쓰기 가설의 재현입니다. AFTER는 중복 가입·미확인 이관을 차단하고 TRANSFER는 선택한 Creator의 Program과 매핑·이력을 함께 변경하며 다른 Creator의 콘텐츠는 유지합니다. 동시 이관이나 Episode/Clip은 검증하지 않습니다.',
  },
  'career.creator-studio.post-commit': {
    introduction: 'Braze/Mixpanel은 실제 외부 호출 없이 기록용 stub으로 대체합니다. 커밋 전 호출 없음·롤백 시 미호출·커밋된 행 조회를 확인하고 비동기 작업 종료까지 기다립니다. RETRY는 Braze가 두 번 실패한 뒤 세 번째 성공, EXHAUSTED는 세 번 실패해 Run FAILED가 되지만 DB 커밋과 Mixpanel 성공은 남습니다. outbox나 프로세스 중단 후 전달 보장은 검증하지 않습니다.',
  },
  'career.mds-distribution.settlement-guard': {
    introduction: '새 Java 21 worker와 임시 MySQL에서 정산 가드를 확인합니다. BEFORE의 PASS는 정산 중 수정되는 문제 재현을 뜻합니다. AFTER는 차단·해제 후 수정, TTL_RECOVERY는 2초 TTL의 지연 평가와 이전 소유자 해제 방지를 검사합니다. 정산 계산이나 수정과 락 획득 사이의 모든 동시성 경합을 보장하는 검증은 아닙니다.',
  },
  'career.mds-distribution.idempotency': {
    introduction: '실제 MySQL에 리스너를 직접 호출해 순차 중복, 두 스레드의 동시 중복, 실패 후 재시도를 확인합니다. RETRY_AFTER_FAILURE는 같은 ID의 실패 payload를 정상 payload로 바꾸는 합성 재시도입니다. PROCESSING_FAILURE는 실패 행과 부분 증거를 남깁니다. 실제 SQS·ACK·DLQ 및 저장 후 프로세스 중단 시 정확히 한 번 처리는 검증하지 않습니다.',
  },
  ...Object.fromEntries(['batch-memory', 'excel-memory'].map(name => ['career.batch-excel.' + name, {
    introduction: '변형마다 새 Java 21 JVM과 임시 MySQL을 사용합니다. 동일한 seed·rows·힙 한도로 비교하세요. 메모리는 20ms 주기와 시작/종료 시점의 heap used 표본이며 실제 최고점·live set·RSS가 아닙니다. GC를 강제하지 않습니다. 앱 시작·seed·결과 검증은 측정에서 제외합니다. FAIL_AFTER_MEASUREMENT는 AFTER 처리와 검증 후 실패를 주입해 부분 증거를 확인하며 배치 재시작 검증은 아닙니다. 합성 입력은 최대 5,000행, JVM heap은 128MiB이며 성능 우위를 보장하지 않습니다.',
  }])),
  'career.equity-system.deletion': {
    introduction: '임시 MySQL에서 같은 입력을 삭제합니다. AFTER는 복합 인덱스와 5,000행 청크를 사용합니다. 전체 시간과 최대 단일 삭제 단계 시간을 구분하세요. 단일 단계 시간은 트랜잭션 시간의 대용 지표이며 실제 잠금 점유 시간을 직접 측정하지 않습니다. 총시간 단축을 보장하지 않습니다.',
  },
  'career.equity-system.redis-lock': {
    introduction: '실제 Redis에서 토큰 소유권과 TTL을 확인합니다. BEFORE는 다른 소유자의 잠금을 지우는 문제와 TTL 부재를 재현해도 기대와 일치하면 PASS입니다. 소유자 교체는 합성 순서로 주입하며 AFTER의 TTL 만료는 실제 관측합니다.',
  },
  'career.equity-system.registration': {
    introduction: '행당 2ms 합성 대기를 포함한 등록 실험입니다. BEFORE는 고정 배치 1,000행을 순차 처리하므로 chunkSize·workers·maxInFlight는 AFTER와 INVALID_ROW에만 적용됩니다. AFTER는 제한된 청크 창의 최대 크기와 실제 제출 대기를 측정합니다. INVALID_ROW는 마지막 행의 share=101로 실패하며 이미 저장된 청크를 되돌리지 않습니다. 과거 업무 성능 수치로 해석하지 마세요.',
  },
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
