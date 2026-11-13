package com.backroom;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * Layering rules from ADR-0001 that Spring Modulith does not cover. Module boundaries are checked by
 * {@link ModuleStructureTests}.
 */
class ArchitectureTests {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.backroom");

    /**
     * Services, policies, entities and repositories speak in domain terms. HTTP statuses, requests and responses
     * belong to controllers and the shared web layer; business failures are {@code shared.error} exceptions.
     */
    @Test
    void onlyTheWebLayerUsesHttpTypes() {
        noClasses()
                .that()
                .resideOutsideOfPackages("com.backroom.shared.web..", "com.backroom.access.security..")
                .and()
                .areNotAnnotatedWith(RestController.class)
                .and()
                .areNotAnnotatedWith(Controller.class)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.http..", "org.springframework.web..", "jakarta.servlet..")
                .because("HTTP is the web layer's concern; services throw shared.error exceptions instead")
                .check(CLASSES);
    }
}
