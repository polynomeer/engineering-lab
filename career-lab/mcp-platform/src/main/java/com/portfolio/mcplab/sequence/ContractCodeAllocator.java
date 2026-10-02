package com.portfolio.mcplab.sequence;

/**
 * [FACT 기반 재현] 계약 코드(정산·외부 유통 연계 핵심 식별자) 채번을 위한
 * range allocation 인터페이스. facts/projects/mcp-platform-revamp.md
 * "4. 시퀀스 구조 개선" — "시퀀스 테이블을 range allocation 방식으로 재설계
 * (한 번의 증가로 여러 건 처리)"를 재현한다.
 *
 * <p>이 인터페이스에는 세 가지 구현체가 있다.</p>
 * <ul>
 *   <li>{@link NonAtomicContractCodeAllocator} — Before: facts "다중 인스턴스에서의
 *       범위 충돌 가능성"에 기록된 실제 결함 구조(읽기-계산-쓰기가 원자적이지 않음).</li>
 *   <li>{@link AtomicUpdateContractCodeAllocator} — After: facts "올바른 개선방안" 2번
 *       (원자적 단일 UPDATE ... LAST_INSERT_ID)을 그대로 구현.</li>
 *   <li>{@link SelectForUpdateContractCodeAllocator} — After 대안: facts "올바른
 *       개선방안" 1번(SELECT ... FOR UPDATE로 같은 트랜잭션 안에서 계산·갱신)을 구현.
 *       facts의 "회고" 항목(SELECT FOR UPDATE가 실제로는 느리지 않았다는 벤치마크
 *       회고)을 참고해 대안으로 함께 둔다.</li>
 * </ul>
 */
public interface ContractCodeAllocator {

    /**
     * seqKey가 가리키는 시퀀스에서 blockSize개짜리 구간을 하나 확보한다.
     * 여러 인스턴스(=여러 스레드)가 동시에 호출해도 서로 겹치지 않는 구간을 받아야 한다
     * — 이 계약(contract, 코드 계약이 아니라 프로그래밍 인터페이스 계약)이 지켜지는지가
     * 이 랩의 핵심 검증 대상이다.
     */
    AllocatedRange allocateBlock(String seqKey, int blockSize);
}
