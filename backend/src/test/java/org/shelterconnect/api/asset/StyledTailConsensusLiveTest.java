package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Exercise the production photo/general + independent anatomy route, without generating or publishing. */
class StyledTailConsensusLiveTest {
    @Test void reviewDisagreementAndMissingTailThroughProductionRoute()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("TAIL_CONSENSUS_LIVE_APPROVED")));
        var json=JsonMapper.builder().build();var out=Path.of(System.getenv("TAIL_CONSENSUS_LIVE_OUTPUT"));Files.createDirectories(out);
        var plan=json.readTree(Files.readAllBytes(Path.of(System.getenv("TAIL_ANATOMY_LIVE_PLAN"))));
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);var client=new OpenAiResponsesClient(ai,json);
        for(String key:List.of("case-a","case-d","case-f")){
            var c=plan.path("cases").valueStream().filter(n->n.path("key").asText().equals(key)).findFirst().orElseThrow();
            var photo=Files.readAllBytes(Path.of(c.path("photo").asText()));assertThat(StyledSpriteCodec.sha(photo)).isEqualTo(c.path("photoSha256").asText());var seeds=new ArrayList<byte[]>();
            for(String d:StyledSpriteCodec.DIRECTIONS){var bytes=Files.readAllBytes(Path.of(c.path("seeds").asText()).resolve(d+".png"));assertThat(StyledSpriteCodec.sha(bytes)).isEqualTo(c.at("/hashes/"+d).asText());seeds.add(bytes);}
            var intent=out.resolve(key+"-intent.json");var saved=out.resolve(key+".json");JsonNode result;
            if(Files.exists(saved))result=json.readTree(Files.readAllBytes(saved));
            else{
                assertThat(Files.exists(intent)).as("Never repeat an unknown paid response").isFalse();
                Files.write(intent,json.writeValueAsBytes(Map.of("inputSha256",StyledSeedQualityAgent.binding(seeds),"photoSha256",StyledSpriteCodec.sha(photo),"rulesSha256",StyledSpriteCodec.qualityRulesSha())),StandardOpenOption.CREATE_NEW);
                result=StyledRecoveryReview.seeds(client,ai,json,photo,seeds,json.createArrayNode(),List.of(),json.createObjectNode());Files.write(saved,json.writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);
            }
            assertThat(result.path("rulesSha256").asText()).isEqualTo(StyledSpriteCodec.qualityRulesSha());
            assertThat(result.path("inputSha256").asText()).isEqualTo(StyledSeedQualityAgent.binding(seeds));
            System.out.println(key+": overall="+result.path("passed")+", tail="+result.at("/tailEvidence/decision")+", issues="+result.path("issues"));
            if(key.equals("case-f"))assertThat(result.path("passed").asBoolean()).isFalse();
            if(key.equals("case-a"))assertThat(result.at("/tailEvidence/passed").asBoolean()).isTrue();
            if(key.equals("case-d"))assertThat(result.at("/tailEvidence/failedDirections").isEmpty()).isTrue();
            // Uncertainty alone cannot buy an edit. Independent non-tail defects may still be repaired.
            if(StyledTailAnatomy.unresolved(result)){
                var repair=StyledSeedRepair.plan(client,json,seeds,result);Files.write(out.resolve(key+"-repair-plan.json"),json.writeValueAsBytes(repair));
                for(var d:result.at("/tailEvidence/uncertainDirections")){
                    var view=result.at("/propertyReview/views").valueStream().filter(v->v.path("direction").asText().equals(d.asText())).findFirst().orElseThrow();
                    if(view.path("repairEvidenceSource").asText().equals("UNRESOLVED_OBSERVATION"))assertThat(repair.path("directions").valueStream().map(JsonNode::asText).toList()).doesNotContain(d.asText());
                    else assertThat(view.path("issues").valueStream().anyMatch(n->!n.asText().startsWith("TAIL_"))
                        || StyledRecoveryReview.BASE.keySet().stream().anyMatch(k->!k.equals("tailPlausible") && !view.path(k).asBoolean())).isTrue();
                }
            }
        }
    }
}
