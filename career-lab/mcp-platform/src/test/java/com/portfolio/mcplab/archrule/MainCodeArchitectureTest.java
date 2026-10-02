package com.portfolio.mcplab.archrule;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * [FACT 기반 재현] {@link LayerArchitectureRules}에 정의된 세 규칙을 실제 프로덕션
 * 코드({@code com.portfolio.mcplab.contract} 이하)에 대해 검증한다 — 규칙을 지키는
 * 코드에서는 실패하지 않는다는 "정상 케이스"를 보여준다.
 *
 * <p>{@link ArchitectureViolationDemoTest}와 정확히 같은 {@link com.tngtech.archunit.lang.ArchRule}
 * 인스턴스를 사용하되, 대상 클래스만 다르다 — 규칙 자체가 아니라 코드가 규칙을
 * 지키는지 여부에 따라 결과가 갈린다는 것을 대비해서 보여주기 위함이다.</p>
 */
class MainCodeArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.portfolio.mcplab.contract");

    @Test
    void controllersMustNotAccessInfrastructure() {
        LayerArchitectureRules.CONTROLLERS_MUST_NOT_ACCESS_INFRASTRUCTURE.check(PRODUCTION_CLASSES);
    }

    @Test
    void domainMustNotDependOnSpringWeb() {
        LayerArchitectureRules.DOMAIN_MUST_NOT_DEPEND_ON_SPRING_WEB.check(PRODUCTION_CLASSES);
    }

    @Test
    void layerDependenciesAreRespected() {
        LayerArchitectureRules.LAYER_DEPENDENCY.check(PRODUCTION_CLASSES);
    }
}
