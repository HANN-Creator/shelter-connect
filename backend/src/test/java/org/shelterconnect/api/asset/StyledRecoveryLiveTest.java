package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Opt-in, photo-authorized replay of production reviewers; never makes a PixelLab request. */
class StyledRecoveryLiveTest {
    @Test void authorizedPilotEvidenceIsReviewedByProductionAgents()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("RECOVERY_LIVE_APPROVED")));
        var json=JsonMapper.builder().build();var output=Path.of(System.getenv("RECOVERY_LIVE_OUTPUT"));Files.createDirectories(output);
        var root=Path.of("scripts/fixtures/pilot10-recovery-v15");byte[] photo=Files.readAllBytes(Path.of(System.getenv("RECOVERY_LIVE_PHOTO")));
        var properties=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        var caseName=new java.util.concurrent.atomic.AtomicReference<String>();
        var client=new OpenAiResponsesClient(properties,json) {
            void audit(JsonNode r){try{Files.write(output.resolve(caseName.get()+"-raw-response.json"),json.writeValueAsBytes(r));}catch(Exception e){throw new RuntimeException(e);}}
            @Override public JsonNode structuredImages(String instructions,String task,Map<String,byte[]> images,Map<String,Object> schema){var r=super.structuredImages(instructions,task,images,schema);audit(r);return r;}
            @Override public JsonNode structuredImage(String instructions,String task,byte[] png,Map<String,Object> schema){var r=super.structuredImage(instructions,task,png,schema);audit(r);return r;}
        };var seedAgent=new StyledSeedQualityAgent(client,properties,json);var motionAgent=new StyledQualityAgent(client,properties,json);
        var policy=json.valueToTree(Map.of("recoveryVersion",StyledRecovery.VERSION,"seedMotionMargin",1));
        // Photo inspection found missing broad white torso markings in historically accepted local base3.
        // Preserve that old PASS as evidence; do not make it a positive correctness label.
        var cases=new LinkedHashMap<String,Boolean>();cases.put("base-1",false);cases.put("base-2",false);cases.put("base-3",false);cases.put("walk-south-initial",false);cases.put("walk-south-selected",true);
        var outcomes=new ArrayList<Object>();
        for(var entry:cases.entrySet()) {
            String name=entry.getKey();caseName.set(name);var base=new ArrayList<byte[]>();var frames=new ArrayList<byte[]>();
            for(String d:StyledSpriteCodec.DIRECTIONS)base.add(Files.readAllBytes(root.resolve(name.startsWith("base")?name+"/"+d+".png":"seeds/"+d+".png")));
            if(name.endsWith("initial"))for(int i=0;i<9;i++)frames.add(Files.readAllBytes(root.resolve("initial-walk-south/"+String.format("%02d",i)+".png")));
            if(name.endsWith("selected"))frames.addAll(StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("selected/walk-south.png"))));
            String input=StyledSpriteCodec.sha(json.writeValueAsBytes(Map.of("photo",StyledSpriteCodec.sha(photo),"base",base.stream().map(StyledSpriteCodec::sha).toList(),"frames",frames.stream().map(StyledSpriteCodec::sha).toList(),"rules",StyledSpriteCodec.qualityRulesSha())));
            Path started=output.resolve(name+"-intent.json"),finished=output.resolve(name+"-review.json");JsonNode report;
            if(Files.exists(finished)){assertThat(Files.readString(started)).isEqualTo(input);report=json.readTree(Files.readAllBytes(finished));}
            else {
                assertThat(Files.exists(started)).as("Do not retry an unknown billed QA outcome").isFalse();Files.writeString(started,input,StandardOpenOption.CREATE_NEW);
                report=name.startsWith("base")?StyledSeedQualityAgent.motionMargin(seedAgent.reviewRecovery(photo,base,json.createArrayNode(),json.readTree(Files.readAllBytes(Path.of(System.getenv("RECOVERY_LIVE_TRAITS"))))),base,policy,json):
                    motionAgent.review(json.valueToTree(Map.of("tailCarriage","UNKNOWN")),base,frames,"WALK","south");
                Files.write(finished,json.writeValueAsBytes(report));
            }
            outcomes.add(Map.of("case",name,"expected",entry.getValue(),"actual",report.path("passed").asBoolean(),"issues",report.path("issues")));
        }
        Files.write(output.resolve("results.json"),json.writeValueAsBytes(outcomes));
        for(var item:outcomes){var result=json.valueToTree(item);assertThat(result.path("actual").asBoolean()).as(result.path("case").asText()+" "+result.path("issues")).isEqualTo(result.path("expected").asBoolean());}
    }
}
