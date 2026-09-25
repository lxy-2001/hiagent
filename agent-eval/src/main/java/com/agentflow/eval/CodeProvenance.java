package com.agentflow.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Local source identity, with content verification for the clean-copy delivery workflow. */
public record CodeProvenance(String status, String codeSha, boolean workingTreeDirty,
                             String trackedDiffHash, int untrackedCount) {
    public static CodeProvenance read(Path root, Path manifest, List<String> requiredFiles) {
        try {
            if (manifest != null) return copied(root, manifest, requiredFiles);
            if (!Files.exists(root.resolve(".git"))) return unknown();
            String sha = new String(git(root, "rev-parse", "HEAD"), java.nio.charset.StandardCharsets.UTF_8).strip();
            if (!sha.matches("[a-f0-9]{40}")) return unknown();
            byte[] status = git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all");
            String statusText = new String(status, java.nio.charset.StandardCharsets.UTF_8);
            int untracked = (int) java.util.Arrays.stream(statusText.split("\u0000")).filter(s -> s.startsWith("?? ")).count();
            return new CodeProvenance("KNOWN", sha, status.length > 0,
                    EvalJson.hash(git(root, "diff", "HEAD", "--binary", "--no-ext-diff")), untracked);
        } catch (IOException | RuntimeException e) {
            if (manifest != null) throw invalid();
            return unknown();
        }
    }
    private static CodeProvenance copied(Path root, Path manifest, List<String> requiredFiles) throws IOException {
        if (Files.size(manifest) > 4 * 1024 * 1024) throw invalid();
        var node = EvalJson.MAPPER.readTree(Files.readAllBytes(manifest));
        EvalDataset.fields(node, "schemaVersion", "codeSha", "workingTreeDirty", "trackedDiffHash", "untrackedCount", "files");
        if (!node.path("schemaVersion").isIntegralNumber() || node.path("schemaVersion").asInt() != 1
                || !node.path("codeSha").asText().matches("[a-f0-9]{40}")
                || !node.path("trackedDiffHash").asText().matches("[a-f0-9]{64}")
                || !node.path("workingTreeDirty").isBoolean()
                || !node.path("untrackedCount").isIntegralNumber() || !node.path("untrackedCount").canConvertToInt()
                || node.path("untrackedCount").asInt() < 0) throw invalid();
        var files = node.path("files");
        if (!files.isArray() || files.isEmpty() || files.size() > 10000) throw invalid();
        Path base = root.toRealPath();
        var seen = new HashSet<String>();
        for (var file : files) {
            EvalDataset.fields(file, "path", "sha256");
            if (!file.path("path").isTextual() || !file.path("sha256").isTextual()) throw invalid();
            String name = file.path("path").asText();
            String lower = name.toLowerCase(Locale.ROOT);
            if (name.isBlank() || name.contains("\\") || name.contains(":") || name.startsWith("/")
                    || List.of(name.split("/")).contains("..") || lower.contains(".env")
                    || lower.contains("secret") || lower.contains("credential") || lower.contains(".git")
                    || !seen.add(name)) throw invalid();
            Path path = base.resolve(name).normalize();
            if (!path.startsWith(base) || !Files.isRegularFile(path) || !path.toRealPath().startsWith(base)) throw invalid();
            String hash = file.path("sha256").asText();
            if (!hash.matches("[a-f0-9]{64}") || !hash.equals(EvalJson.hash(Files.readAllBytes(path)))) throw invalid();
        }
        if (!seen.containsAll(requiredFiles)) throw invalid();
        return new CodeProvenance("COPIED_SOURCE", node.path("codeSha").asText(), node.path("workingTreeDirty").asBoolean(),
                node.path("trackedDiffHash").asText(), node.path("untrackedCount").asInt());
    }
    private static byte[] git(Path root, String... args) throws IOException {
        var command = new java.util.ArrayList<String>(); command.add("git"); command.addAll(List.of(args));
        // A temporary output file avoids deadlock when the tracked diff exceeds the pipe buffer.
        Path output = Files.createTempFile("agent-eval-git-", ".tmp");
        try {
            Process process = new ProcessBuilder(command).directory(root.toFile())
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Git timed out"); }
            } catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException("Git interrupted"); }
            if (process.exitValue() != 0) throw new IOException("Git unavailable");
            return Files.readAllBytes(output);
        } finally { Files.deleteIfExists(output); }
    }
    private static CodeProvenance unknown() { return new CodeProvenance("UNKNOWN", null, false, null, 0); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_CODE_PROVENANCE"); }
}
