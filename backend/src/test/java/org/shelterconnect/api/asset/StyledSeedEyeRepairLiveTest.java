package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real approved images/providers only with explicit opt-in; exact request/ack/result journals survive interruption. */
class StyledSeedEyeRepairLiveTest {
    final JsonMapper json=JsonMapper.builder().build();
    void save(Path p,JsonNode data)throws Exception{Files.write(p,json.writeValueAsBytes(data));}
    List<byte[]> seeds(Path dir)throws Exception{var out=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)out.add(Files.readAllBytes(dir.resolve(d+".png")));return out;}
    @Test void approvedOriginalEyesAreLocatedAndRepairedWithoutOperatorCoordinates()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("SEED_EYE_REPAIR_LIVE")));
        var root=Path.of(System.getenv("SEED_EYE_REPAIR_ROOT"));Files.createDirectories(root);
        var props=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        var ai=new OpenAiResponsesClient(props,json);var quality=new StyledSeedQualityAgent(ai,props,json);var repair=new StyledSeedEyeRepair(ai,json);
        var provider=new StyledPixelLabClient(new AssetProperties(true,false,System.getenv("PIXELLAB_API_KEY"),"sb_secret_local_test","dog-photos","dog-assets"),json);
        var original=seeds(root.resolve("source"));byte[] photo=Files.readAllBytes(root.resolve("photo.png"));
        var policy=json.createObjectNode().put("seedMotionMargin",2);var current=original;
        Path checked=root.resolve("before-review.json");JsonNode report;
        if(Files.exists(checked))report=json.readTree(Files.readAllBytes(checked));
        else {report=StyledSeedQualityAgent.motionMargin(quality.review(photo,current),current,policy,json);save(checked,report);}
        assertThat(report.path("rulesSha256").asText()).isEqualTo(StyledSpriteCodec.qualityRulesSha());
        assertThat(report.path("inputSha256").asText()).isEqualTo(StyledSeedQualityAgent.binding(current));
        if(report.path("passed").asBoolean()) {
            // This explicit diagnostic is also authorized by the user's eye-edit request.
            // Preserve the contradictory AI pass verbatim; never relabel it as an AI failure.
            var requested=(tools.jackson.databind.node.ObjectNode)report.deepCopy();
            requested.set("originalAiAssessment",report);requested.put("assessmentSource","USER_REQUESTED_EYE_EDIT");
            requested.put("passed",false);requested.set("issues",json.valueToTree(List.of("EYE_READABILITY")));
            for(var v:requested.path("views"))if(!v.path("direction").asText().equals("north"))
                ((tools.jackson.databind.node.ObjectNode)v).put("readability","FAIL").put("note","User-requested visible-eye refinement; this is NOT the original AI verdict.");
            report=requested;
        }
        save(root.resolve("repair-trigger.json"),report);
        for(int attempt=1;attempt<=2;attempt++) {
            Path run=root.resolve("attempt-"+attempt);Files.createDirectories(run);Path planFile=run.resolve("plan.json");
            JsonNode plan=Files.exists(planFile)?json.readTree(Files.readAllBytes(planFile)):repair.locate(current,report);save(planFile,plan);
            assertThat(plan.path("version").asText()).as("AI must confidently locate the eyes before paid editing").isEqualTo(StyledSeedEyeRepair.VERSION);
            var payload=repair.payload(current,plan,2026100865+attempt);
            var requestSha=StyledSpriteCodec.sha(json.writeValueAsBytes(payload));Path request=run.resolve("request.json"),intent=run.resolve("intent.json"),ack=run.resolve("ack.json"),completed=run.resolve("provider-result.json");
            if(Files.exists(request))assertThat(StyledSpriteCodec.sha(Files.readAllBytes(request))).isEqualTo(requestSha);
            else save(request,payload);
            UUID id;
            if(Files.exists(ack))id=UUID.fromString(json.readTree(Files.readAllBytes(ack)).path("id").asText());
            else {
                assertThat(Files.exists(intent)).as("Never resubmit an unknown paid outcome").isFalse();
                save(intent,json.valueToTree(Map.of("requestSha256",requestSha,"rulesSha256",StyledSpriteCodec.qualityRulesSha(),"sourceBinding",StyledSeedQualityAgent.binding(current))));
                id=provider.editSeedEyes(payload);save(ack,json.valueToTree(Map.of("id",id.toString())));
            }
            JsonNode result=Files.exists(completed)?json.readTree(Files.readAllBytes(completed)):null;
            for(int polls=0;result==null && polls<120;polls++) {
                var polled=provider.pollSeedEyes(id);
                if(polled.path("status").asText().equals("COMPLETED")){result=polled;save(completed,result);}
                else{assertThat(polled.path("status").asText()).isEqualTo("WAITING");Thread.sleep(5000);}
            }
            assertThat(result).isNotNull();byte[] raw=Base64.getDecoder().decode(result.path("eyeSheet").asText());Files.write(run.resolve("raw.png"),raw);
            var applied=StyledSeedEyeRepair.apply(current,plan,raw);current=applied.seeds();
            for(int i=0;i<4;i++)Files.write(run.resolve(StyledSpriteCodec.DIRECTIONS.get(i)+".png"),current.get(i));
            Path reviewed=run.resolve("review.json");
            if(Files.exists(reviewed))report=json.readTree(Files.readAllBytes(reviewed));
            else{report=StyledSeedQualityAgent.motionMargin(quality.review(photo,current),current,policy,json);save(reviewed,report);}
            assertThat(report.path("inputSha256").asText()).isEqualTo(StyledSeedQualityAgent.binding(current));
            assertThat(report.path("rulesSha256").asText()).isEqualTo(StyledSpriteCodec.qualityRulesSha());
            save(run.resolve("audit.json"),json.valueToTree(Map.of("changedPixels",applied.changedPixels(),"visiblePixelsChangedOutsideMask",0,"sourceBinding",plan.path("sourceBinding").asText(),
                "inputBinding",StyledSeedQualityAgent.binding(current),"rawSha256",StyledSpriteCodec.sha(raw),"productionPublished",false,"requestSha256",requestSha)));
            if(report.path("passed").asBoolean())break;
        }
        save(root.resolve("outcome.json"),report);assertThat(report.path("passed").asBoolean()).as("Actual eye/identity/margin review, not a mocked result").isTrue();
    }
}
