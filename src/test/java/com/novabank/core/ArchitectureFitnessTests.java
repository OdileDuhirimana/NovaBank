package com.novabank.core;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Mechanically enforces the layering rules the README's architecture section and Mermaid
 * diagram claim ("controller -> service -> repository -> model, dependencies flow inward"),
 * rather than relying on documentation and manual code review alone to catch a regression.
 *
 * This is a direct response to a real violation the code review found: {@code AdminController}
 * used to inject {@code AccountRepository}/{@code AuditLogRepository}/{@code FraudLogRepository}
 * directly, bypassing the service layer entirely (fixed by introducing {@code AdminService}).
 * These tests exist so that specific regression can never silently reappear, and so the same
 * class of violation is caught for every controller/service/repository added in the future, not
 * just the one instance a human reviewer happened to notice this time.
 */
class ArchitectureFitnessTests {

    private static final com.tngtech.archunit.core.domain.JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.novabank.core");

    @Test
    void controllersMustNotDependDirectlyOnRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .because("controllers must go through a service — see AdminService, introduced "
                        + "specifically to fix AdminController's prior direct repository access.");

        rule.check(CLASSES);
    }

    @Test
    void servicesMustNotDependOnControllers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAPackage("..controller..")
                .because("dependencies must flow controller -> service, never the reverse.");

        rule.check(CLASSES);
    }

    @Test
    void repositoriesMustNotDependOnServicesOrControllers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..")
                .because("the repository layer is the innermost layer and must have no outward "
                        + "dependency on layers built on top of it.");

        rule.check(CLASSES);
    }

    @Test
    void modelClassesMustNotDependOnAnyOuterLayer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..model..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..controller..", "..service..", "..repository..", "..dto..")
                .because("domain entities must be the most stable, dependency-free layer in the codebase.");

        rule.check(CLASSES);
    }
}
