package com.agentflow.eval;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Publishes the two complete artifacts with one directory rename; never overwrites an older run. */
public final class ReportWriter {
    public Path writeComparison(Path directory, tools.jackson.databind.JsonNode comparison) throws IOException {
        Files.createDirectories(directory);
        Path destination = directory.resolve(java.util.UUID.randomUUID().toString());
        Path temporary = Files.createTempDirectory(directory, ".comparison-");
        try {
            Files.write(temporary.resolve("compare.json"), EvalJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(comparison));
            var markdown = new StringBuilder("# Agent comparison\n\nStatus: ").append(escape(comparison.path("status").asText()))
                    .append("\n\n| Case | Repeat | Baseline | Candidate | Change |\n| --- | --- | --- | --- | --- |\n");
            for (var pair : comparison.path("pairs")) markdown.append("| ").append(escape(pair.path("caseId").asText())).append(" | ")
                    .append(pair.path("repeat").asInt()).append(" | ").append(escape(pair.path("baselineStatus").asText())).append(" | ")
                    .append(escape(pair.path("candidateStatus").asText())).append(" | ").append(escape(pair.path("change").asText())).append(" |\n");
            markdown.append("\nReasons: ").append(escape(comparison.path("reasons").toString())).append("\n");
            Files.writeString(temporary.resolve("compare.md"), markdown, StandardCharsets.UTF_8);
            return Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(temporary)) {
                Files.deleteIfExists(temporary.resolve("compare.json")); Files.deleteIfExists(temporary.resolve("compare.md")); Files.deleteIfExists(temporary);
            }
        }
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("|", "&#124;").replace("\n", " ").replace("\r", " ").replace("`", "&#96;");
    }
    public Path write(Path directory, RunReport report) throws IOException {
        Files.createDirectories(directory);
        Path destination = directory.resolve(report.id());
        if (Files.exists(destination)) throw new FileAlreadyExistsException("Report already exists");
        Path temporary = Files.createTempDirectory(directory, ".evaluation-");
        try {
            Files.write(temporary.resolve("report.json"), EvalJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report.document()));
            StringBuilder markdown = new StringBuilder("# Agent evaluation\n\n");
            markdown.append("Evaluation: `").append(report.id()).append("`\n\nGate: **")
                    .append(report.passed() ? "PASS" : "FAIL").append("**\n\n")
                    .append("| Case | Repeat | Status |\n| --- | --- | --- |\n");
            for (var c : report.document().path("cases")) markdown.append("| ").append(c.path("caseId").asText())
                    .append(" | ").append(c.path("repeat").asInt()).append(" | ").append(c.path("caseStatus").asText()).append(" |\n");
            markdown.append("\nFixture results measure mechanisms, not model intelligence. Unknown usage is not zero cost.\n");
            Files.writeString(temporary.resolve("report.md"), markdown, StandardCharsets.UTF_8);
            return Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(temporary)) {
                Files.deleteIfExists(temporary.resolve("report.json"));
                Files.deleteIfExists(temporary.resolve("report.md"));
                Files.deleteIfExists(temporary);
            }
        }
    }
}
