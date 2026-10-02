package com.portfolio.mcplab.archrule;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [NEW-DESIGN] {@link LayerArchitectureRules}와 완전히 같은 규칙 인스턴스를, 일부러
 * 규칙을 위반하도록 만든 {@code com.portfolio.mcplab.badexample} 패키지에 대해
 * 검증한다 — "규칙을 위반하는 클래스에서는 테스트가 실패한다"는 것을 보여주는
 * 데모 테스트다.
 *
 * <p>badexample 클래스들은 프로덕션 코드가 아니라 이 데모만을 위한 최소 예시다
 * (실제로 배포되거나 다른 코드에서 참조되지 않는다). ArchUnit은 규칙 위반 시
 * {@code check()} 호출에서 (일반) {@link AssertionError}를 던지므로, 여기서는 그
 * 예외가 실제로 던져지는지를 {@code assertThrows}로 검증한다 — "테스트가 실패하는
 * 것을 보여달라"는 요구를 "위반 시 예외가 던져짐을 단언하는 통과 테스트"로 안전하게
 * 표현한 것이다(무작정 실패하는 테스트를 커밋해두면 빌드가 항상 깨지므로).</p>
 */
class ArchitectureViolationDemoTest {

    private static final JavaClasses BAD_EXAMPLE_CLASSES = new ClassFileImporter()
            .importPackages("com.portfolio.mcplab.badexample");

    @Test
    void controllerAccessingInfrastructureDirectly_violatesTheRule() {
        AssertionError error = assertThrows(AssertionError.class,
                () -> LayerArchitectureRules.CONTROLLERS_MUST_NOT_ACCESS_INFRASTRUCTURE.check(BAD_EXAMPLE_CLASSES));

        assertThat(error.getMessage())
                .contains("BadContractController")
                .contains("BadContractRepository");
    }

    @Test
    void domainTouchingSpringWeb_violatesTheRule() {
        AssertionError error = assertThrows(AssertionError.class,
                () -> LayerArchitectureRules.DOMAIN_MUST_NOT_DEPEND_ON_SPRING_WEB.check(BAD_EXAMPLE_CLASSES));

        assertThat(error.getMessage()).contains("BadDomainTouchingSpringWeb");
    }

    @Test
    void layerDependencyRule_isViolatedByBadExamplePackage() {
        assertThrows(AssertionError.class,
                () -> LayerArchitectureRules.LAYER_DEPENDENCY.check(BAD_EXAMPLE_CLASSES));
    }
}
