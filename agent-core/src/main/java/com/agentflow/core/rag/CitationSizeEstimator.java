package com.agentflow.core.rag;

import java.util.Collection;

/** Conservative UTF-8 JSON upper bound; this class does not serialize JSON. */
public final class CitationSizeEstimator {
    public long estimate(Collection<Citation> citations) {
        long total = 64;
        for (var c : citations) {
            total += 512;
            for (String value : new String[]{c.id(), c.snapshotId(), c.docId(), c.documentVersion(), c.chunkId(),
                    c.contentHash(), c.sourcePath(), c.title(), c.excerpt()}) {
                for (int i = 0; i < value.length(); i++) {
                    char character = value.charAt(i);
                    if (character < 32 || character == '"' || character == '\\') { total += 6; }
                    else if (character < 128) { total++; }
                    else if (character < 2048) { total += 2; }
                    else if (Character.isSurrogate(character)) { total += 6; }
                    else { total += 3; }
                }
            }
        }
        return total;
    }
}
