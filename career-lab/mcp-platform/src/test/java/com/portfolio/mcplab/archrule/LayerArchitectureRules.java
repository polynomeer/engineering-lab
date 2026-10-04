package com.portfolio.mcplab.archrule;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * [FACT 기반 재현] facts/projects/mcp-platform-revamp.md
 * "5. 아키텍처 가드레일 구축" — "레이어 간 의존성 얽힘, 서비스 계층 우회 호출"을 막기
 * 위해 ArchUnit으로 레이어 의존성 규칙을 테스트 코드로 명문화했다는 Decision을
 * design/mcp-platform-revamp.md 5.5절 수준까지 구체화한 것.
 *
 * <p>규칙을 한 곳에 모아두고, {@link com.portfolio.mcplab.archrule.MainCodeArchitectureTest}
 * (실제 프로덕션 코드 — 통과해야 함)와
 * {@link com.portfolio.mcplab.archrule.ArchitectureViolationDemoTest}
 * (일부러 위반하도록 만든 {@code badexample} 패키지 — 실패해야 함) 양쪽에서 똑같은
 * {@link ArchRule} 인스턴스를 재사용한다. 같은 규칙이 한쪽에서는 통과하고 다른 쪽에서는
 * 실패한다는 것을 보여주는 것이 이 시나리오의 핵심이다.</p>
 *
 * <p>패키지 매칭은 ArchUnit의 {@code "..api.."} 문법(경로 어딘가에 해당 세그먼트를
 * 포함하는 모든 패키지)을 쓰므로, 루트 패키지가 {@code com.portfolio.mcplab.contract}든
 * {@code com.portfolio.mcplab.badexample}이든 규칙 정의를 바꾸지 않고 그대로 적용할 수
 * 있다.</p>
 */
public final class LayerArchitectureRules {

    private LayerArchitectureRules() {
    }

    /** design 5.5절 controllers_should_not_access_infrastructure 그대로. */
    public static final ArchRule CONTROLLERS_MUST_NOT_ACCESS_INFRASTRUCTURE =
            noClasses().that().resideInAPackage("..api..")
                    .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                    .because("api(Controller) 계층은 application(Service)만 거쳐 infrastructure에 접근해야 한다 "
                            + "— facts 5번 항목 '서비스 계층 우회 호출' 금지");

    /** design 5.5절 domain_should_not_depend_on_spring_web 그대로. */
    public static final ArchRule DOMAIN_MUST_NOT_DEPEND_ON_SPRING_WEB =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..")
                    .because("도메인은 순수 객체여야 하며 프레젠테이션 프레임워크에 묶이면 안 된다");

    /** design 5.5절 layer_dependency(layeredArchitecture)를 이 랩의 레이어 이름으로 재구성. */
    public static final ArchRule LAYER_DEPENDENCY = Architectures.layeredArchitecture()
            .consideringAllDependencies()
            .layer("Api").definedBy(resideInAPackage("..api.."))
            .layer("Application").definedBy(resideInAPackage("..application.."))
            .layer("Domain").definedBy(resideInAPackage("..domain.."))
            .layer("Infrastructure").definedBy(resideInAPackage("..infrastructure.."))
            .whereLayer("Api").mayNotBeAccessedByAnyLayer()
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure");
}
