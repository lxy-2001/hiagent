package com.agentflow.core.rag;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CitationValidatorTest {
    @Test
    void validatesReservedMarkersIncludingCodeBlocksAndRequiresDeliveredEvidence() throws Exception {
        var ledger = new EvidenceLedger();
        ledger.bind("c", RetrievalEvidenceTest.payload(List.of(RetrievalEvidenceTest.chunk(1, "Java"), RetrievalEvidenceTest.chunk(2, "Spring"))));
        var validator = new CitationValidator();
        var valid = validator.validate("[S2] then [S1] again [S2]", true, Set.of("S1", "S2"), ledger);
        assertTrue(valid.valid());
        assertEquals(List.of("S2", "S1"), valid.citations().stream().map(Citation::id).toList());
        for (String answer : List.of("[S99]", "[S0]", "[S01]", "[S-1]", "[S1", "[Something]", "```[S33]```", "[S" + "9".repeat(80) + "]", "[S1]".repeat(257))) {
            assertEquals("CITATION_INVALID", validator.validate(answer, false, Set.of("S1"), ledger).errorCode());
        }
        assertEquals("CITATION_INVALID", validator.validate("[S2]", false, Set.of("S1"), ledger).errorCode());
        assertEquals("INSUFFICIENT_EVIDENCE", validator.validate("no evidence", true, Set.of(), ledger).errorCode());
        assertTrue(validator.validate("[Java]", false, Set.of(), ledger).valid());
    }
}
