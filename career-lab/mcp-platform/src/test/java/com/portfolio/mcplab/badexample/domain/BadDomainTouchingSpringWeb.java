package com.portfolio.mcplab.badexample.domain;

import org.springframework.web.bind.annotation.RestController;

/**
 * [NEW-DESIGN] 일부러 ArchUnit 규칙을 위반하도록 만든 데모 클래스 — 도메인 객체가
 * 프레젠테이션 프레임워크(Spring Web)에 의존하면 안 된다는 규칙을 어긴다.
 * {@link com.portfolio.mcplab.archrule.ArchitectureViolationDemoTest} 전용이며 실제
 * 애플리케이션에서 쓰이지 않는다.
 */
public class BadDomainTouchingSpringWeb {

    // 위반 지점: domain 계층 클래스가 org.springframework.web.. 패키지에 의존.
    private final Class<RestController> leakedWebDependency = RestController.class;

    public Class<RestController> leakedWebDependency() {
        return leakedWebDependency;
    }
}
