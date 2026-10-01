package com.portfolio.mcplab.badexample.api;

import com.portfolio.mcplab.badexample.infrastructure.BadContractRepository;

/**
 * [NEW-DESIGN] 일부러 ArchUnit 규칙을 위반하도록 만든 데모 클래스.
 * facts "5. 아키텍처 가드레일 구축" Problem이 말하는 "서비스 계층 우회 호출"을
 * 그대로 재현한다 — api(Controller)가 application(Service)을 거치지 않고
 * infrastructure(Repository)를 직접 호출한다.
 *
 * <p>{@link com.portfolio.mcplab.archrule.ArchitectureViolationDemoTest}에서만
 * 사용되며, 실제 애플리케이션 컨텍스트에는 절대 등록되지 않는다(Spring 스테레오타입
 * 어노테이션이 없다).</p>
 */
public class BadContractController {

    // 위반 지점: api 계층 클래스가 infrastructure 계층 클래스에 직접 의존.
    private final BadContractRepository repository = new BadContractRepository();

    public void createContractWithoutGoingThroughService(String contractCode) {
        repository.save(contractCode);
    }
}
