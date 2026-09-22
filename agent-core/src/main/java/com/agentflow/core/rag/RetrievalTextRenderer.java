package com.agentflow.core.rag;

import java.util.List;

/** Complete, line-prefixed source representation; document text cannot create structural lines. */
public final class RetrievalTextRenderer {
    public String render(RetrievalPayload payload, List<Citation> citations, boolean trimmed, RetrievalPayload.EmptyReason empty) {
        var text = new StringBuilder("retrieval-v1 mode=").append(payload.mode()).append(" semantic=")
                .append(payload.semanticState()).append(" keyword=").append(payload.keywordState())
                .append(" reason=").append(payload.degradationCode() == null ? "NONE" : payload.degradationCode())
                .append(" empty=").append(empty).append(" trimmed=").append(trimmed).append('\n');
        for (var c : citations) {
            text.append("source [").append(c.id()).append("]\npath=").append(escape(c.sourcePath()))
                    .append("\ntitle=").append(escape(c.title())).append("\nsnapshot=").append(c.snapshotId())
                    .append(" doc=").append(c.docId()).append(" version=").append(c.documentVersion()).append(" chunk=").append(c.chunkId())
                    .append("\nrange=").append(c.start()).append(':').append(c.end()).append(" hash=").append(c.contentHash())
                    .append("\nuntrusted-text-begin\n");
            for (String line : c.excerpt().split("\n", -1)) { text.append("| ").append(line).append('\n'); }
            text.append("untrusted-text-end\n");
        }
        return text.toString();
    }
    private String escape(String value) { return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t"); }
}
