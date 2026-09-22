package com.agentflow.rag.corpus;

import java.util.List;
import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** Deterministic normalization and code-point windows for the offline corpus. */
public final class DocumentChunker {
    public static final String NORMALIZATION_VERSION = "text-nfc-lf-v1";
    public static final String CHUNKER_VERSION = "codepoint-window-v1";
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(?:[\"']?(?:api[-_ ]?key|token|secret|password)[\"']?\\s*[:=]\\s*[\"']?[^,;\\s\"'}]+"
                    + "|Bearer\\s+[A-Za-z0-9._~+/=-]+|-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----|\\bsk-[A-Za-z0-9_-]{8,})");
    public record Document(String docId, String version, String relativePath, String title,
                           String contentHash, String text, List<Chunk> chunks) {
        public Document { chunks = List.copyOf(chunks); }
    }
    public record Chunk(String docId, String documentVersion, String chunkId, String contentHash,
                        int ordinal, int start, int end, String text) { }

    public Document read(String relativePath, byte[] bytes, int size, int overlap) {
        if (size < 200 || size > 800 || overlap < 0 || overlap > Math.min(200, size - 1)) {
            throw new IllegalArgumentException("INDEX_PROFILE_MISMATCH");
        }
        if (bytes.length > 1_048_576) {
            throw new IllegalArgumentException("CORPUS_LIMIT");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("INVALID_UTF8");
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        text = Normalizer.normalize(text.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC);
        if (text.codePoints().anyMatch(c -> c < 32 && c != '\n' && c != '\t')) {
            throw new IllegalArgumentException("INVALID_UTF8");
        }
        if (SECRET.matcher(text).find() || SECRET.matcher(relativePath).find()) {
            throw new IllegalArgumentException("SECRET_CONTENT");
        }
        String title = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        if (relativePath.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
            title = text.lines().filter(line -> line.startsWith("# ") && !line.substring(2).isBlank())
                    .map(line -> line.substring(2).strip()).findFirst().orElse(title);
        }
        if (title.length() > 120) {
            int end = Character.isHighSurrogate(title.charAt(119)) ? 119 : 120;
            title = title.substring(0, end);
        }
        String docId = CorpusHash.tuple("doc-v1", relativePath);
        String version = CorpusHash.tuple("version-v1", docId, NORMALIZATION_VERSION, text);
        var chunks = new ArrayList<Chunk>();
        if (!text.isBlank()) {
            int length = text.codePointCount(0, text.length());
            for (int start = 0; start < length; start += size - overlap) {
                int end = Math.min(start + size, length);
                String content = text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
                String hash = CorpusHash.content(content);
                String id = CorpusHash.tuple("chunk-v1", version, CHUNKER_VERSION, Integer.toString(size),
                        Integer.toString(overlap), Integer.toString(start), Integer.toString(end), hash);
                chunks.add(new Chunk(docId, version, id, hash, chunks.size(), start, end, content));
                if (end == length) { break; }
            }
        }
        return new Document(docId, version, relativePath, title, CorpusHash.content(text), text, chunks);
    }
}
