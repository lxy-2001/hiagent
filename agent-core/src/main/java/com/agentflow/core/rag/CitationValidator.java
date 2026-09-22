package com.agentflow.core.rag;

import java.util.List;
import java.util.Set;

public final class CitationValidator {
    public record CitationValidation(List<Citation> citations, String errorCode) {
        public CitationValidation { citations = List.copyOf(citations); }
        public boolean valid() { return errorCode == null; }
    }
    public CitationValidation validate(String answer, boolean requireEvidence, Set<String> eligible, EvidenceLedger ledger) {
        var ids = new java.util.LinkedHashSet<String>();
        int occurrences = 0;
        for (int offset = 0; offset < answer.length();) {
            int start = answer.indexOf("[S", offset);
            if (start < 0) { break; }
            int end = answer.indexOf(']', start + 2);
            if (end < 0 || end - start > 64 || ++occurrences > 256) { return failure("CITATION_INVALID"); }
            String id = answer.substring(start + 1, end);
            if (!id.matches("S(?:[1-9]|[12][0-9]|3[0-2])") || !eligible.contains(id)) { return failure("CITATION_INVALID"); }
            ids.add(id);
            offset = end + 1;
        }
        if (ids.isEmpty() && requireEvidence) { return failure("INSUFFICIENT_EVIDENCE"); }
        try { return new CitationValidation(ledger.selectInAnswerOrder(List.copyOf(ids)), null); }
        catch (IllegalArgumentException invalid) { return failure("CITATION_INVALID"); }
    }
    private CitationValidation failure(String code) { return new CitationValidation(List.of(), code); }
}
