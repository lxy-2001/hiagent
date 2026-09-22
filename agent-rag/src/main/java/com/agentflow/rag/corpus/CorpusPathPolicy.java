package com.agentflow.rag.corpus;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.text.Normalizer;

public final class CorpusPathPolicy {
    public record Source(String relativePath, byte[] bytes) {
        public Source { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public List<Source> read(Path root) {
        if (root == null) { throw new IllegalArgumentException("INVALID_PATH"); }
        for (Path segment : root) {
            if (segment.toString().equals("..")) { throw new IllegalArgumentException("INVALID_PATH"); }
        }
        Path absolute = root.toAbsolutePath().normalize();
        try {
            checkAncestors(absolute);
            if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("INVALID_PATH");
            }
            var result = new ArrayList<Source>();
            var names = new HashSet<String>();
            long total = 0;
            // No FOLLOW_LINKS: every encountered entry is inspected before use.
            try (var paths = Files.walk(absolute)) {
                var iterator = paths.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    var attributes = checkedAttributes(path);
                    if (attributes.isDirectory()) { continue; }
                    if (!attributes.isRegularFile()) { throw new IllegalArgumentException("INVALID_PATH"); }
                    String relative = Normalizer.normalize(absolute.relativize(path).toString().replace('\\', '/'),
                            Normalizer.Form.NFC);
                    if (relative.length() > 256 || relative.contains(":")
                            || relative.codePoints().anyMatch(c -> c < 32)
                            || !names.add(relative.toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("INVALID_PATH");
                    }
                    String lower = relative.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith(".md") && !lower.endsWith(".txt")) {
                        throw new IllegalArgumentException("UNSUPPORTED_SOURCE");
                    }
                    if (result.size() >= 100 || attributes.size() > 1_048_576) {
                        throw new IllegalArgumentException("CORPUS_LIMIT");
                    }
                    byte[] bytes;
                    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                        bytes = input.readNBytes(1_048_577);
                    }
                    total += bytes.length;
                    if (bytes.length > 1_048_576 || total > 20L * 1_048_576) {
                        throw new IllegalArgumentException("CORPUS_LIMIT");
                    }
                    result.add(new Source(relative, bytes));
                }
            }
            result.sort((a, b) -> Arrays.compareUnsigned(a.relativePath().getBytes(StandardCharsets.UTF_8),
                    b.relativePath().getBytes(StandardCharsets.UTF_8)));
            return List.copyOf(result);
        } catch (IOException | java.io.UncheckedIOException error) {
            throw new IllegalArgumentException("INVALID_PATH");
        }
    }

    static void checkAncestors(Path absolute) throws IOException {
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            checkedAttributes(current);
        }
    }

    private static BasicFileAttributes checkedAttributes(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        // The Windows NIO provider marks junction/reparse entries as symbolic links or other.
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IllegalArgumentException("INVALID_PATH");
        }
        return attributes;
    }
}
