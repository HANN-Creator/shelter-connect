package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Opt-in paid canary of the real production payload/client/reviewer, isolated from production DB/publication. */
class StyledRecoveryRepairLiveTest {
    @Test void authorizedPhotoGroundedBaseRepairIsReviewedWithoutForcingAcceptance()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("RECOVERY_REPAIR_APPROVED")));
        var json=JsonMapper.builder().build();var output=Path.of(System.getenv("RECOVERY_REPAIR_OUTPUT"));Files.createDirectories(output);
        var previous=Path.of(System.getenv("RECOVERY_REPAIR_REVIEW"));
        var initial=json.readTree(Files.readAllBytes(previous));assertThat(initial.path("passed").asBoolean()).isFalse();
        var photo=Files.readAllBytes(Path.of(System.getenv("RECOVERY_LIVE_PHOTO")));
        var traits=json.readTree(Files.readAllBytes(Path.of(System.getenv("RECOVERY_LIVE_TRAITS"))));
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        var current=new java.util.concurrent.atomic.AtomicReference<Path>();
        var client=new OpenAiResponsesClient(ai,json) {
            @Override public JsonNode structuredImages(String instructions,String task,Map<String,byte[]> images,Map<String,Object> schema) {
                var r=super.structuredImages(instructions,task,images,schema);
                try{Files.write(current.get().resolve("review-response.json"),json.writeValueAsBytes(r));}catch(Exception e){throw new RuntimeException(e);}return r;
            }
        };
        var qa=new StyledSeedQualityAgent(client,ai,json);
        var provider=new StyledPixelLabClient(new AssetProperties(true,false,System.getenv("PIXELLAB_API_KEY"),"sb_secret_unused","dog-photos","dog-assets"),json);
        var seeds=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)seeds.add(Files.readAllBytes(Path.of("scripts/fixtures/pilot10-recovery-v15/base-3/"+d+".png")));
        JsonNode report=initial;int calls=0;
        for(int attempt=1;attempt<=3 && !report.path("passed").asBoolean();attempt++) {
            Path dir=output.resolve("attempt-"+attempt);Files.createDirectories(dir);current.set(dir);
            var payload=StyledRecovery.basePayload(json,seeds,report,94200+attempt);String hash=StyledSpriteCodec.sha(json.writeValueAsBytes(payload));
            Path intent=dir.resolve("intent.txt"),receipt=dir.resolve("provider-id.txt"),resultPath=dir.resolve("provider.json");UUID id;
            if(Files.exists(receipt)){assertThat(Files.readString(intent)).isEqualTo(hash);id=UUID.fromString(Files.readString(receipt));}
            else {assertThat(Files.exists(intent)).as("Unknown billed submission must not be replayed").isFalse();Files.writeString(intent,hash,StandardOpenOption.CREATE_NEW);id=provider.editSeeds(payload);calls++;Files.writeString(receipt,id.toString(),StandardOpenOption.CREATE_NEW);}
            JsonNode result=null;
            if(Files.exists(resultPath))result=json.readTree(Files.readAllBytes(resultPath));
            else {
                long until=System.nanoTime()+java.time.Duration.ofMinutes(5).toNanos();
                while(System.nanoTime()<until){result=provider.pollSeeds(id);if(!result.path("status").asText().equals("WAITING"))break;Thread.sleep(5000);}
                assertThat(result.path("status").asText()).isEqualTo("COMPLETED");Files.write(resultPath,json.writeValueAsBytes(result));
            }
            seeds=new ArrayList<>();for(String d:StyledSpriteCodec.DIRECTIONS){byte[] png=StyledPixelLabClient.decode(result.at("/directions/"+d).asText());StyledSpriteCodec.nativeFrame(png);Files.write(dir.resolve(d+".png"),png);seeds.add(png);}
            var policy=json.valueToTree(Map.of("recoveryVersion",StyledRecovery.VERSION,"seedMotionMargin",1));
            Path reviewed=dir.resolve("review.json"),reviewIntent=dir.resolve("review-intent.txt");
            if(Files.exists(reviewed))report=json.readTree(Files.readAllBytes(reviewed));
            else {assertThat(Files.exists(reviewIntent)).as("Unknown billed review must not be repeated").isFalse();Files.writeString(reviewIntent,StyledSeedQualityAgent.binding(seeds),StandardOpenOption.CREATE_NEW);
                report=StyledSeedQualityAgent.motionMargin(qa.reviewRecovery(photo,seeds,json.createArrayNode(),traits),seeds,policy,json);Files.write(reviewed,json.writeValueAsBytes(report));}
        }
        Files.write(output.resolve("result.json"),json.writeValueAsBytes(Map.of("passed",report.path("passed").asBoolean(),"newPixelLabCalls",calls,"report",report,"productionPublished",false)));
        assertThat(report.path("passed").asBoolean()).as("Actual real-provider automatic repair must pass without suppressing issues").isTrue();
    }
}
