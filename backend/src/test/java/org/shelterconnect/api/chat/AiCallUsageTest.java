package org.shelterconnect.api.chat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AiCallUsageTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private String response(String input, String cached, String output, String reasoning) {
        return "{\"usage\":{\"input_tokens\":" + input + ",\"output_tokens\":" + output
                + ",\"input_tokens_details\":{\"cached_tokens\":" + cached
                + "},\"output_tokens_details\":{\"reasoning_tokens\":" + reasoning + "}}}";
    }
    @Test void preservesCachedAndReasoningSubtotalsWithoutDoubleCounting() {
        assertThat(AiCallUsage.read(json.readTree(response("1200", "1000", "150", "100"))))
                .contains(new AiCallUsage(1200, 1000, 150, 100));
    }
    @Test void unknownMalformedOverflowAndContradictoryUsageNeverBecomesZeroCost() {
        for (String input : new String[]{"{}", "{\"usage\":{}}", response("null", "0", "10", "0"),
                response("\"1200\"", "0", "10", "0"), response("1.5", "0", "10", "0"),
                response("-1", "0", "10", "0"), response("10", "11", "10", "0"),
                response("10", "0", "10", "11"), response("10", "0", "-1", "0"),
                response("2000001", "0", "10", "0"), response("18446744073709551616", "0", "10", "0")})
            assertThat(AiCallUsage.read(json.readTree(input))).isEmpty();
    }
}
