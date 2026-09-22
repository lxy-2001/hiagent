package com.agentflow.core.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreDependencyBoundaryTest {

    private static final String[] ALLOWED_PACKAGES = {
            "com.agentflow.core..",
            "java.lang..",
            "java.time..",
            "java.util.."
    };

    @Test
    void productionCoreDoesNotDependOnFrameworkTransportStorageOrProviderTypes() {
        coreDependencyRule().check(new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.agentflow.core"));
    }

    @Test
    void rejectsUnapprovedJdkNetworkDependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(UrlDependencyFixture.class);

        assertThrows(AssertionError.class, () -> coreDependencyRule().check(fixture));
    }

    private ArchRule coreDependencyRule() {
        return classes()
                .that().resideInAnyPackage("com.agentflow.core..")
                .should().onlyDependOnClassesThat(resideInAnyPackage(ALLOWED_PACKAGES)
                        .or(equivalentTo(java.security.MessageDigest.class))
                        .or(equivalentTo(java.security.NoSuchAlgorithmException.class))
                        .or(equivalentTo(java.nio.charset.StandardCharsets.class))
                        .or(equivalentTo(java.nio.charset.Charset.class))
                        .or(equivalentTo(java.text.Normalizer.class))
                        .or(equivalentTo(java.text.Normalizer.Form.class)));
    }

    static final class UrlDependencyFixture {
        private static java.net.URL identity(java.net.URL value) {
            return value;
        }
    }
}
