package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in. Only the preserved failed observation is injected; focus uses the real production request. */
class StyledTailCoordinateFocusLiveTest {
    @Test void actualLunaReadsUnchangedDogThroughProductionFocusInput()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("TAIL_FOCUS_LIVE_APPROVED")));
        var json=JsonMapper.builder().build();var out=Path.of(System.getenv("TAIL_FOCUS_LIVE_OUTPUT"));Files.createDirectories(out);
        byte[] photo=Files.readAllBytes(Path.of(System.getenv("TAIL_FOCUS_LIVE_PHOTO")));var seeds=StyledTailCoordinateFocusTest.seeds();
        var old=json.readTree(Files.readAllBytes(StyledTailCoordinateFocusTest.FIXTURE.resolve("deployed-review.json")));
        assertThat(StyledSpriteCodec.sha(photo)).isEqualTo(old.path("photoSha256").asText());
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        var binding=json.valueToTree(Map.of("photoSha256",StyledSpriteCodec.sha(photo),"inputSha256",StyledSeedQualityAgent.binding(seeds),
            "rulesSha256",StyledSpriteCodec.qualityRulesSha(),"model",ai.model()));
        Path intent=out.resolve("intent.json"),saved=out.resolve("review.json");JsonNode result;
        if(Files.exists(saved)){
            assertThat(json.readTree(Files.readAllBytes(intent))).isEqualTo(binding);result=json.readTree(Files.readAllBytes(saved));
        }else{
            assertThat(Files.exists(intent)).as("Do not repeat an unknown paid submission").isFalse();Files.write(intent,json.writeValueAsBytes(binding),StandardOpenOption.CREATE_NEW);
            var client=spy(new OpenAiResponsesClient(ai,json));
            doReturn(old.at("/tailEvidence/observation")).doCallRealMethod().when(client).structuredImages(anyString(),anyString(),anyMap(),anyMap());
            result=StyledSeedTailEvidence.review(client,ai,json,photo,seeds);Files.write(saved,json.writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);
            verify(client,times(2)).structuredImages(anyString(),anyString(),anyMap(),anyMap());
        }
        assertThat(result.path("refinements").size()).isEqualTo(1);
        assertThat(result.at("/refinements/0/direction").asText()).isEqualTo("east");
        assertThat(result.path("refinementFailureCode").isMissingNode()).isTrue();
        var replay=StyledSeedTailEvidence.grounded(json,result.path("observation"),seeds);
        assertThat(replay.at("/pixelAudit/east/completeContour").asBoolean()).isTrue();
        assertThat(replay.path("passed").asBoolean()).as("Actual model must support the result; no manual override").isTrue();
        assertThat(result.at("/observation/views/0")).isEqualTo(old.at("/tailEvidence/observation/views/0"));
        assertThat(result.path("inputSha256")).isEqualTo(old.path("inputSha256"));
        // This checks coordinate observation on the preserved photo/PNGs, not final server quality approval or motion.
    }
}
