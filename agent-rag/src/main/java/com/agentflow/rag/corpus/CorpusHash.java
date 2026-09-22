package com.agentflow.rag.corpus;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Versioned identities encode every tuple component with its UTF-8 byte length. */
public final class CorpusHash {
    private CorpusHash() { }

    public static String tuple(String... components) {
        var digest = digest();
        for (String component : components) {
            byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String content(String text) {
        return bytes(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String bytes(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
