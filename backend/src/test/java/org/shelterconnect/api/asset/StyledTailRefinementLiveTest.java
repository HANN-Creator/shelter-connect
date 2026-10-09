package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in real Luna re-observation. Saved intent prevents repeating an unknown paid request. */
class StyledTailRefinementLiveTest {
    @Test void actualModelReobservesThePreservedCoordinateFailure()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("TAIL_REFINEMENT_LIVE_APPROVED")));
        var json=JsonMapper.builder().build();var root=StyledTailRefinementTest.FIXTURE;
        var out=Path.of(System.getenv("TAIL_REFINEMENT_LIVE_OUTPUT"));Files.createDirectories(out);
        byte[] photo=Files.readAllBytes(Path.of(System.getenv("TAIL_REFINEMENT_LIVE_PHOTO")));
        var seeds=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)seeds.add(Files.readAllBytes(root.resolve(d+".png")));
        var old=json.readTree(Files.readAllBytes(root.resolve("deployed-review.json")));
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        Path intent=out.resolve("refinement-intent.json"),saved=out.resolve("refinement-review.json");JsonNode result;
        var binding=json.valueToTree(Map.of("photoSha256",StyledSpriteCodec.sha(photo),"inputSha256",StyledSeedQualityAgent.binding(seeds),
            "rulesSha256",StyledSpriteCodec.qualityRulesSha(),"originalObservationPhotoSha256",old.path("photoSha256").asText()));
        if(Files.exists(saved)){
            assertThat(json.readTree(Files.readAllBytes(intent))).isEqualTo(binding);result=json.readTree(Files.readAllBytes(saved));
        }else{
            assertThat(Files.exists(intent)).as("Do not repeat unknown paid QA submission").isFalse();Files.write(intent,json.writeValueAsBytes(binding),StandardOpenOption.CREATE_NEW);
            // Inject only the immutable prior response; the one refinement uses the actual provider and production code.
            var client=spy(new OpenAiResponsesClient(ai,json));
            doReturn(old.at("/tailEvidence/observation")).doCallRealMethod().when(client).structuredImages(anyString(),anyString(),anyMap(),anyMap());
            result=StyledSeedTailEvidence.review(client,ai,json,photo,seeds);Files.write(saved,json.writeValueAsBytes(result));
        }
        assertThat(result.path("observationHistory").size()).isEqualTo(2);
        assertThat(result.path("refinementFailureCode").isMissingNode()).isTrue();
        var replay=StyledSeedTailEvidence.grounded(json,result.path("observation"),seeds);
        assertThat(replay.at("/pixelAudit/east/completeContour").asBoolean()).isTrue();
        // Valid coordinates do not imply semantic certainty. The actual reply remained .72: keep the hold.
        for(var view:replay.at("/observation/views"))if(view.path("confidence").asDouble()<.75)
            assertThat(replay.path("passed").asBoolean()).isFalse();
        // This is localization verification, not blanket approval of the known side-tail mismatch.
        assertThat(old.at("/generalPropertyReview/tailConsistent").asBoolean()).isFalse();
    }
}
