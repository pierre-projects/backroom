package com.backroom;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Enforces the module rules in docs/architecture.md and ADR-0001: each top-level package is a module, modules
 * depend on each other without cycles, and only through types in another module's base package (or a declared
 * named interface). Sub-packages are internal to their module.
 */
class ModuleStructureTests {
    private final ApplicationModules modules = ApplicationModules.of(BackroomApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        modules.verify();
    }

    /** Writes module diagrams and a per-module overview to target/spring-modulith-docs. */
    @Test
    void writeModuleDocumentation() {
        new Documenter(modules).writeDocumentation();
    }
}
