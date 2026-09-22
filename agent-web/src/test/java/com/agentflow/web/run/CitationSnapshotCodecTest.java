package com.agentflow.web.run;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CitationSnapshotCodecTest {
    private final CitationSnapshotCodec codec = new CitationSnapshotCodec();

    @Test void roundTripsAndReadsLegacyNull() throws Exception {
        var citations = List.of(CitationProjectionTest.citation("中文 😀 quote \" \\ \n"));
        assertThat(codec.decode(codec.encode(citations))).isEqualTo(citations);
        assertThat(codec.decode(null)).isEmpty();
        assertThat(codec.encode(List.of())).contains("\"schemaVersion\":1", "\"citations\":[]");
    }

    @Test void rejectsMalformedSchemasDuplicateIdsAndOversizedData() throws Exception {
        var c = CitationProjectionTest.citation("valid");
        String valid = new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("schemaVersion", 1, "citations", List.of(c)));
        for (String json : List.of("{}", "[]", "{broken", valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("valid", "wrong"), "x".repeat(65537), valid.replace("\"schemaVersion\":1", "\"extra\":true,\"schemaVersion\":1"))) {
            assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(CitationSnapshotCodec.UnavailableException.class);
        }
        assertThatThrownBy(() -> codec.encode(List.of(c, c))).isInstanceOf(IllegalArgumentException.class);
    }
}
