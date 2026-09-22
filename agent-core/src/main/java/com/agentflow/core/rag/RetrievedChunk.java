package com.agentflow.core.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/** A complete source excerpt; citation identifiers are assigned only by the runtime. */
public record RetrievedChunk(String snapshotId, String docId, String documentVersion, String chunkId,
                             String contentHash, String relativePath, String title,
                             int start, int end, String text) {
    public RetrievedChunk {
        requireHash(snapshotId);
        requireHash(docId);
        requireHash(documentVersion);
        requireHash(chunkId);
        requireHash(contentHash);
        requireText(relativePath, 256);
        requireText(title, 120);
        requireText(text, 1600);
        if (relativePath.isBlank() || title.isBlank()) {
            throw new IllegalArgumentException("source metadata must not be blank");
        }
        if (relativePath.startsWith("/") || relativePath.contains("\\") || relativePath.contains(":")) {
            throw new IllegalArgumentException("invalid source path");
        }
        for (String segment : relativePath.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("invalid source path segment");
            }
        }
        if (relativePath.codePoints().anyMatch(c -> c < 32) || start < 0 || end <= start || end > 1_048_576
                || end - start > 800 || text.codePointCount(0, text.length()) != end - start) {
            throw new IllegalArgumentException("invalid source range or path");
        }
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
            if (!actual.equals(contentHash)) {
                throw new IllegalArgumentException("source content hash mismatch");
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static void requireHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid source hash");
        }
    }

    private static void requireText(String text, int maxLength) {
        if (text == null || text.isEmpty() || text.length() > maxLength
                || !Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException("invalid source text");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32 && c != '\n' && c != '\t') {
                throw new IllegalArgumentException("source contains control characters");
            }
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException("source contains isolated surrogate");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("source contains isolated surrogate");
            }
        }
    }
}
