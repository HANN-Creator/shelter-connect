package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** Opt-in fixed-image production evaluation, with write-ahead intents and all raw provider replies. */
class StyledTailConsistencyLiveTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Path fixtures=StyledTailObservationConsistencyTest.ROOT;
    List<byte[]> seeds(Path root)throws Exception {
        var out=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)out.add(Files.readAllBytes(root.resolve(d+".png")));return out;
    }
    void write(Path path,Object value)throws Exception {Files.write(path,json.writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);}
    @Test void fixedPositiveAndNegativeCasesAgainstTheRealProvider()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("TAIL_CONSISTENCY_LIVE_APPROVED")));
        var output=Path.of(System.getenv("TAIL_CONSISTENCY_LIVE_OUTPUT"));Files.createDirectories(output);
        var source=Path.of(System.getenv("TAIL_CONSISTENCY_SOURCE"));
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        var expected=new LinkedHashMap<String,Boolean>();
        expected.put("black-production",true);
        for(String key:List.of("case-a","case-c","case-d","case-i"))expected.put(key,true);
        for(String key:List.of("case-b","case-f","case-g","case-j","cream-motion"))expected.put(key,false);
        var outcomes=json.createArrayNode();var failures=new ArrayList<String>();
        for(var item:expected.entrySet()) {
            String key=item.getKey();var dir=output.resolve(key);Files.createDirectories(dir);
            var pixels=seeds(key.startsWith("case-")?Path.of("scripts/fixtures/tail-anatomy-v21",key):fixtures.resolve(key.equals("cream-motion")?"cream":"black"));
            byte[] photo=key.equals("black-production")?Files.readAllBytes(source.resolve("source-3/server-normalized.png")):pixels.get(0);
            if(key.equals("black-production")) {
                var original=json.readTree(Files.readAllBytes(fixtures.resolve("black/deployed-review.json")));
                assertThat(StyledSpriteCodec.sha(photo)).isEqualTo(original.path("photoSha256").asText());
                assertThat(StyledSeedQualityAgent.binding(pixels)).isEqualTo(original.path("inputSha256").asText());
            }
            if(!Files.exists(dir.resolve("scope.json")))write(dir.resolve("scope.json"),Map.of(
                "scope",key.equals("black-production")?"FULL_PRODUCTION_BASE_REVIEW":key.equals("cream-motion")?"PRODUCTION_MOTION_REVIEW":"TAIL_ANATOMY_ONLY",
                "note",key.startsWith("case-")?"Archived photo observation reused; only target sprites enter fresh vision. photoSha256 is a fixture sentinel, not an actual-photo binding or publication authorization.":"Original unchanged production inputs."));
            var intent=Map.of("key",key,"expected",item.getValue(),"inputSha256",StyledSeedQualityAgent.binding(pixels),
                "photoSha256",StyledSpriteCodec.sha(photo),"rulesSha256",StyledSpriteCodec.qualityRulesSha());
            Path saved=dir.resolve("result.json");JsonNode result;
            if(Files.exists(saved)) {
                assertThat(json.readTree(Files.readAllBytes(dir.resolve("intent.json")))).isEqualTo(json.valueToTree(intent));
                result=json.readTree(Files.readAllBytes(saved));
            } else {
                assertThat(Files.exists(dir.resolve("intent.json"))).as("Never duplicate an unknown paid outcome: "+key).isFalse();
                write(dir.resolve("intent.json"),intent);
                var client=new OpenAiResponsesClient(ai,json) {
                    int calls;
                    @Override public JsonNode structuredImagesWithReasoning(String instructions,String task,Map<String,byte[]> images,Map<String,Object> schema,String effort) {
                        int n=++calls;try {
                            var hashes=new LinkedHashMap<String,String>();images.forEach((k,v)->hashes.put(k,StyledSpriteCodec.sha(v)));
                            write(dir.resolve("call-"+n+"-request.json"),Map.of("instructions",instructions,"task",task,"imageHashes",hashes,"schema",schema,"effort",effort));
                            var response=super.structuredImagesWithReasoning(instructions,task,images,schema,effort);
                            write(dir.resolve("call-"+n+"-response.json"),response);return response;
                        } catch(Exception e) {throw new IllegalStateException("Fixed evaluation call failed; inspect its intent, do not reroll: "+key+"/"+n,e);}
                    }
                };
                if(key.equals("black-production")) {
                    var traits=json.readTree(Files.readAllBytes(source.resolve("source-3/traits.json")));
                    result=new StyledSeedQualityAgent(client,ai,json).reviewRecovery(photo,pixels,json.createArrayNode(),traits);
                } else if(key.equals("cream-motion")) {
                    var frames=StyledSpriteCodec.frames(Files.readAllBytes(fixtures.resolve("cream/idle-north.png")));
                    result=new StyledQualityAgent(client,ai,json).review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),
                        pixels.stream().map(StyledSpriteCodec::paddedSeed).toList(),frames,"IDLE","north");
                } else {
                    // Reuse the archived real-photo observation; never describe a sprite as an actual photograph.
                    var archived=json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/tail-anatomy-v21",key,"observed-r4.json"))).path("observation");
                    var photoObservation=json.createObjectNode();photoObservation.set("photoTail",archived.path("photoTail"));photoObservation.set("photoEvidence",archived.path("photoEvidence"));
                    result=StyledTailAnatomy.review(client,ai,json,photo,pixels,photoObservation);
                }
                write(saved,result);
            }
            assertThat(result.path("rulesSha256").asText()).isEqualTo(StyledSpriteCodec.qualityRulesSha());
            boolean pass=result.path("passed").asBoolean();
            var row=json.createObjectNode().put("case",key).put("expectedPass",item.getValue()).put("actualPass",pass);
            row.set("decision",result.path("qualityDecision").isMissingNode()?result.path("motionDecision").isMissingNode()?result.path("decision"):result.path("motionDecision"):result.path("qualityDecision"));
            row.set("issues",result.path("issues"));outcomes.add(row);
            Files.write(output.resolve("results.json"),json.writeValueAsBytes(outcomes));System.out.println(row);
            if(pass!=item.getValue())failures.add(key);
            if(key.equals("black-production") && pass)assertThat(StyledTailAnatomy.boundPass(result)).isTrue();
        }
        assertThat(failures).as("Do not relabel or reroll completed real failures").isEmpty();
    }
}
