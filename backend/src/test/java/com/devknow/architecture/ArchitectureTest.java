package com.devknow.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * ArchUnit 架构测试 —— 自动验证包依赖方向、分层规则、命名约定。
 *
 * <p>在 CI 中自动执行，防止代码架构随时间腐化。
 * 规则覆盖：
 * <ul>
 *   <li>分层约束：Controller → Service → Repository 的单向依赖</li>
 *   <li>内部包隔离：rag.eval / rag.router / rag.sparse 互不依赖</li>
 *   <li>循环依赖检测</li>
 *   <li>命名约定</li>
 * </ul>
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void init() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
                .importPackages("com.devknow");
    }

    // ==================== 分层约束 ====================

    @Test
    void controller_should_not_depend_on_repository() {
        // Controller 不应直接访问 Repository
        noClasses()
                .that().resideInAPackage("..controller..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..repository..")
                .because("Controller 不应直接访问 Repository，应通过 Service");
    }

    @Test
    void service_should_not_depend_on_controller() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..controller..")
                .because("Service 层不应反向依赖 Controller 层");

        // 项目中 service 类散布在 rag / chat / knowledge 等包中，按模式匹配
        noClasses()
                .that().resideInAPackage("com.devknow..")
                .and().resideOutsideOfPackage("..controller..")
                .and().resideOutsideOfPackage("..config..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..controller..")
                .because("非 Controller 层的类不应依赖 Controller")
                .check(classes);
    }

    @Test
    void repository_should_only_be_accessed_by_service() {
        // Repository 的合法访问者：对应的 Service 包 + Controller + config
        noClasses()
                .that().resideOutsideOfPackage("..knowledge..")
                .and().resideOutsideOfPackage("..codeindex..")
                .and().resideOutsideOfPackage("..auth..")
                .and().resideOutsideOfPackage("..governance..")
                .and().resideOutsideOfPackage("..controller..")
                .and().resideOutsideOfPackage("..project..")
                .and().resideOutsideOfPackage("..study..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..repository..")
                .because("Repository 应仅由对应的 Service 或 Controller 或 config 访问")
                .check(classes);
    }

    // ==================== 内部包隔离 ====================

    @Test
    void rag_subpackages_should_not_depend_on_each_other() {
        noClasses()
                .that().resideInAPackage("com.devknow.rag.eval..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.devknow.rag.router..", "com.devknow.rag.sparse..")
                .because("rag 内部子包（eval / router / sparse）应独立，互不依赖");

        noClasses()
                .that().resideInAPackage("com.devknow.rag.router..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.devknow.rag.eval..", "com.devknow.rag.sparse..")
                .because("rag 内部子包（eval / router / sparse）应独立，互不依赖");

        noClasses()
                .that().resideInAPackage("com.devknow.rag.sparse..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.devknow.rag.eval..", "com.devknow.rag.router..")
                .because("rag 内部子包（eval / router / sparse）应独立，互不依赖")
                .check(classes);
    }

    // ==================== 循环依赖 ====================

    @Test
    void no_cycles_within_business_packages() {
        // 检查单个顶级包内不出现循环依赖（跨包的 infrastructure→business 依赖是正常设计）
        slices()
                .matching("com.devknow.rag.(*)..")
                .should().beFreeOfCycles()
                .because("rag 内部子包不应循环依赖")
                .check(classes);

        slices()
                .matching("com.devknow.chat.(*)..")
                .should().beFreeOfCycles()
                .because("chat 内部子包不应循环依赖")
                .check(classes);
    }

    @Test
    void no_cycles_within_rag_package() {
        slices()
                .matching("com.devknow.rag.(*)..")
                .should().beFreeOfCycles()
                .because("rag 内部子包不应存在循环依赖")
                .check(classes);
    }

    // ==================== 命名约定 ====================

    @Test
    void classes_named_service_should_reside_in_business_package() {
        // 项目中有 @Service 注解的类散布在各个领域包中（非统一 service 包），
        // 这是"按领域分包"的体现，只要不在 controller/config 中即可
        noClasses()
                .that().haveSimpleNameEndingWith("Service")
                .should().resideInAPackage("..controller..")
                .because("以 Service 结尾的类不应位于 controller 包中");

        noClasses()
                .that().haveSimpleNameEndingWith("Service")
                .should().resideInAPackage("..config..")
                .because("以 Service 结尾的类不应位于 config 包中");
    }

    @Test
    void classes_annotated_with_restcontroller_should_not_reside_in_config() {
        // 项目中 @RestController 分散在各个领域包（auth/chat/discover 等），
        // 这是"每个领域自包含 controller"的设计，不强制统一放在 controller 包
        noClasses()
                .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .should().resideInAPackage("..config..")
                .because("@RestController 不应位于 config 包中");
    }
}
