package com.agentflow.core.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class CoreDependencyBoundaryTest {

    private static final String[] FORBIDDEN_PACKAGES = {
            "org.springframework..",
            "org.springframework.ai..",
            "jakarta.persistence..",
            "javax.persistence..",
            "org.hibernate..",
            "jakarta.servlet..",
            "javax.servlet..",
            "java.sql..",
            "javax.sql..",
            "org.springframework.jdbc..",
            "java.net.http..",
            "org.springframework.web..",
            "org.apache.http..",
            "okhttp3..",
            "org.springframework.data.redis..",
            "io.lettuce..",
            "redis.clients..",
            "io.qdrant..",
            "com.qdrant..",
            "com.openai..",
            "dev.langchain4j..",
            "ai.djl.."
    };

    @Test
    void productionCoreDoesNotDependOnFrameworkTransportStorageOrProviderTypes() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN_PACKAGES);

        rule.check(new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.agentflow.core"));
    }
}
