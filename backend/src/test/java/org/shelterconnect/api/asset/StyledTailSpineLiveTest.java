package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Opt-in full production BASE review of the same deployed pixels. Never generates, publishes or edits a job. */
class StyledTailSpineLiveTest {
    @Test void unchangedDeployedBasePassesFreshFullReview() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("TAIL_SPINE_LIVE_APPROVED")));
        var json=JsonMapper.builder().build();var root=Path.of("scripts/fixtures/tail-spine-v31");
        var output=Path.of(System.getenv("TAIL_SPINE_LIVE_OUTPUT"));Files.createDirectories(output);
        var evidence=json.readTree(Files.readAllBytes(root.resolve("evidence.json")));
        var photo=Files.readAllBytes(Path.of(System.getenv("TAIL_SPINE_LIVE_PHOTO")));
        assertThat(StyledSpriteCodec.sha(photo)).isEqualTo(evidence.path("sourcePhotoSha256").asText());
        var traits=json.readTree(Files.readAllBytes(Path.of(System.getenv("TAIL_SPINE_LIVE_TRAITS"))));
        var seeds=new ArrayList<byte[]>();
        for(String d:StyledSpriteCodec.DIRECTIONS) {
            var bytes=Files.readAllBytes(root.resolve(d+".png"));
            assertThat(StyledSpriteCodec.sha(bytes)).isEqualTo(evidence.at("/sha256/"+d+".png").asText());seeds.add(bytes);
        }
        var binding=json.valueToTree(Map.of("inputSha256",StyledSeedQualityAgent.binding(seeds),"photoSha256",StyledSpriteCodec.sha(photo),
            "traitsSha256",StyledSpriteCodec.sha(json.writeValueAsBytes(traits)),"rulesSha256",StyledSpriteCodec.qualityRulesSha()));
        var receipt=output.resolve("full-base-review.json");var intent=output.resolve("full-base-intent.json");JsonNode report;
        if(Files.exists(receipt)) {
            assertThat(json.readTree(Files.readAllBytes(intent))).isEqualTo(binding);
            report=json.readTree(Files.readAllBytes(receipt));
        } else {
            assertThat(Files.exists(intent)).as("An unknown paid outcome must not be repeated").isFalse();
            Files.write(intent,json.writeValueAsBytes(binding),StandardOpenOption.CREATE_NEW);
            var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
            var agent=new StyledSeedQualityAgent(new OpenAiResponsesClient(ai,json),ai,json);
            report=StyledSeedQualityAgent.motionMargin(agent.reviewRecovery(photo,seeds,json.createArrayNode(),traits),seeds,
                json.valueToTree(Map.of("recoveryVersion",StyledRecovery.VERSION,"seedMotionMargin",1)),json);
            Files.write(receipt,json.writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
        }
        assertThat(report.path("inputSha256")).isEqualTo(binding.path("inputSha256"));
        assertThat(report.path("rulesSha256")).isEqualTo(binding.path("rulesSha256"));
        assertThat(report.path("photoSha256")).isEqualTo(binding.path("photoSha256"));
        assertThat(report.path("passed").asBoolean()).as("Actual BASE quality, not a forced approval: "+report.path("issues")).isTrue();
        assertThat(StyledTailAnatomy.boundPass(report)).isTrue();
    }
}
