package org.shelterconnect.sample;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AiLiveCheckTest {
    @Test void reviewedCasesUseOnlyFictionalPublicDogsAndConfirmedEvidence() throws Exception {
        var json=JsonMapper.builder().build();
        var fixture=json.readTree(Files.readString(Path.of("sample-data/dataset.json")));
        var cases=json.readTree(Files.readString(Path.of("sample-data/ai/cases.json")));
        assertThat(fixture.path("fictional").asBoolean()).isTrue();
        assertThat(cases.size()).isEqualTo(10);
        var ids=new HashSet<String>();
        for(var c:cases) {
            assertThat(ids.add(c.path("id").asText())).isTrue();
            assertThat(c.path("dog").asText()).isIn("1","2","3","4");
            assertThat(c.path("question").asText()).isNotBlank();
            assertThat(c.path("review").asText()).isNotBlank();
            if(c.path("expected").asText().equals("grounded")) {
                String evidence="02300000-0000-4000-8000-000000000"+c.path("evidence").asText();
                var records=new ArrayList<tools.jackson.databind.JsonNode>();
                fixture.path("observations").forEach(o->{if(o.path("id").asText().equals(evidence)) records.add(o);});
                assertThat(records).hasSize(1);
                assertThat(records.getFirst().path("status").asText()).isEqualTo("CONFIRMED");
                assertThat(records.getFirst().path("dogId").asText()).isEqualTo("02200000-0000-4000-8000-00000000000"+c.path("dog").asText());
            } else assertThat(c.path("expected").asText()).isEqualTo("unknown");
        }
    }
}
