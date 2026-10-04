package com.portfolio.mcplab.badexample.infrastructure;

/**
 * [NEW-DESIGN] {@link com.portfolio.mcplab.archrule.ArchitectureViolationDemoTest}
 * 전용 더미 클래스. 실제로 아무 것도 하지 않는다 — 오직 "infrastructure 패키지에
 * 존재하는 클래스"라는 사실만 필요하다. 프로덕션 코드가 아니며 어디서도 실제로
 * 사용되지 않는다.
 */
public class BadContractRepository {

    public void save(String contractCode) {
        // 의도적으로 비워둠 — ArchUnit 위반 데모를 위한 최소 더미.
    }
}
