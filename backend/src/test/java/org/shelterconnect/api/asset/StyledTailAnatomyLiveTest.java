package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Bounded real-provider comparison. Labels never enter model requests; completed replies cannot be rerolled. */
class StyledTailAnatomyLiveTest {
    @Test void compareFixedBlindedCases()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("TAIL_ANATOMY_LIVE_APPROVED")));
        var json=JsonMapper.builder().build();var out=Path.of(System.getenv("TAIL_ANATOMY_LIVE_OUTPUT"));Files.createDirectories(out);
        var plan=json.readTree(Files.readAllBytes(Path.of(System.getenv("TAIL_ANATOMY_LIVE_PLAN"))));
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);var client=new OpenAiResponsesClient(ai,json);
        var outcomes=json.createArrayNode();int index=0;
        for(var c:plan.path("cases")){
            var photo=Files.readAllBytes(Path.of(c.path("photo").asText()));assertThat(StyledSpriteCodec.sha(photo)).isEqualTo(c.path("photoSha256").asText());
            var seeds=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS){var b=Files.readAllBytes(Path.of(c.path("seeds").asText()).resolve(d+".png"));assertThat(StyledSpriteCodec.sha(b)).isEqualTo(c.at("/hashes/"+d).asText());seeds.add(b);}
            var methods="false".equals(System.getenv("TAIL_ANATOMY_COMPARE_LEGACY"))?List.of("anatomy"):
                index++%2==0?List.of("legacy","anatomy"):List.of("anatomy","legacy");
            for(String method:methods){
                String key=c.path("key").asText()+"-"+method;var saved=out.resolve(key+".json");var intent=out.resolve(key+"-intent.json");JsonNode result;
                if(Files.exists(saved))result=json.readTree(Files.readAllBytes(saved));
                else{
                    assertThat(Files.exists(intent)).as("Unknown provider outcome must not be duplicated: "+key).isFalse();
                    Files.write(intent,json.writeValueAsBytes(Map.of("inputSha256",StyledSeedQualityAgent.binding(seeds),"photoSha256",StyledSpriteCodec.sha(photo),"rulesSha256",StyledSpriteCodec.qualityRulesSha(),"method",method)),StandardOpenOption.CREATE_NEW);
                    long started=System.nanoTime();
                    result=method.equals("legacy")?StyledSeedTailEvidence.review(client,ai,json,photo,seeds):StyledTailAnatomy.review(client,ai,json,photo,seeds);
                    ((tools.jackson.databind.node.ObjectNode)result).put("elapsedMilliseconds",(System.nanoTime()-started)/1_000_000);
                    Files.write(saved,json.writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);
                }
                assertThat(result.path("inputSha256").asText()).isEqualTo(StyledSeedQualityAgent.binding(seeds));
                assertThat(result.path("photoSha256").asText()).isEqualTo(StyledSpriteCodec.sha(photo));
                assertThat(result.path("rulesSha256").asText()).isEqualTo(StyledSpriteCodec.qualityRulesSha());
                var row=json.createObjectNode().put("case",c.path("key").asText()).put("method",method).put("passed",result.path("passed").asBoolean());
                row.set("expected",c.path("expected"));row.set("decision",result.path("decision"));row.set("uncertainDirections",result.path("uncertainDirections"));
                row.put("calls",method.equals("legacy")?1+result.path("refinements").size():result.path("reviewCalls").asInt());outcomes.add(row);
                Files.write(out.resolve("results.json"),json.writeValueAsBytes(outcomes));
                System.out.println(key+": "+row);
            }
        }
        // Do not turn a real failed evaluation into a green test by changing its labels.
        for(var r:outcomes)if(r.path("method").asText().equals("anatomy") && r.path("expected").isBoolean())
            assertThat(r.path("passed").asBoolean()).as(r.path("case").asText()).isEqualTo(r.path("expected").asBoolean());
    }
}
