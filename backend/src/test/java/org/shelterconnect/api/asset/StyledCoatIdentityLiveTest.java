package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** One opt-in independent coat/identity observation on original deployed pixels; no generation or publication. */
class StyledCoatIdentityLiveTest {
    @Test void observesNonCoatIdentityOnTheExactFailedDeployedBranch()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("COAT_IDENTITY_LIVE_APPROVED")));
        var json=JsonMapper.builder().build();var output=Path.of(System.getenv("COAT_IDENTITY_LIVE_OUTPUT"));Files.createDirectories(output);
        var root=Path.of("scripts/fixtures/coat-identity-v33");
        var evidence=json.readTree(Files.readAllBytes(root.resolve("evidence.json")));
        var photo=Files.readAllBytes(Path.of(System.getenv("COAT_IDENTITY_LIVE_PHOTO")));
        var traits=json.readTree(Files.readAllBytes(Path.of(System.getenv("COAT_IDENTITY_LIVE_TRAITS"))));
        var seeds=CoatIdentityReplay.seeds();var original=CoatIdentityReplay.archived(json);
        assertThat(StyledSpriteCodec.sha(photo)).isEqualTo(evidence.path("sourcePhotoSha256").asText());
        assertThat(StyledSeedQualityAgent.binding(seeds)).isEqualTo(evidence.path("inputSha256").asText());
        var binding=json.valueToTree(Map.of("inputSha256",StyledSeedQualityAgent.binding(seeds),"photoSha256",StyledSpriteCodec.sha(photo),
            "traitsSha256",StyledSpriteCodec.sha(json.writeValueAsBytes(traits)),"rulesSha256",StyledSpriteCodec.qualityRulesSha(),
            "originalReportSha256",StyledSpriteCodec.sha(Files.readAllBytes(root.resolve("deployed-review.json")))));
        var receipt=output.resolve("independent-coat-identity.json");var intent=output.resolve("intent.json");JsonNode coat;
        if(Files.exists(receipt)) {
            assertThat(json.readTree(Files.readAllBytes(intent))).isEqualTo(binding);coat=json.readTree(Files.readAllBytes(receipt));
        } else {
            assertThat(Files.exists(intent)).as("Never repeat an unknown paid outcome").isFalse();
            var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
            Files.write(intent,json.writeValueAsBytes(binding),StandardOpenOption.CREATE_NEW);
            coat=StyledCoatReview.review(new OpenAiResponsesClient(ai,json),json,photo,seeds,traits,original.at("/coatEvidence/originalPropertyReview"));
            Files.write(receipt,json.writeValueAsBytes(coat),StandardOpenOption.CREATE_NEW);
        }
        var replay=CoatIdentityReplay.review(json,photo,seeds,traits,coat.path("observation"));
        Files.write(output.resolve("production-review-replay.json"),json.writeValueAsBytes(replay));
        assertThat(replay.path("inputSha256")).isEqualTo(binding.path("inputSha256"));
        assertThat(replay.path("rulesSha256")).isEqualTo(binding.path("rulesSha256"));
        assertThat(replay.path("photoSha256")).isEqualTo(binding.path("photoSha256"));
        assertThat(replay.path("passed").asBoolean()).as("Real independent observation; other observations replayed: "+replay.path("issues")).isTrue();
        assertThat(StyledTailAnatomy.boundPass(replay)).isTrue();
    }
}
