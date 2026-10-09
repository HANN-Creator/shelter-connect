package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** Explicit paid canary. Durable receipts prevent repeating an uncertain submission on restart. */
class StyledSelectedRepairLiveTest {
    final JsonMapper json=JsonMapper.builder().build();
    @Test void actualFailedViewsAreRepairedWithoutReplacingPassingViews()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("SELECTED_REPAIR_APPROVED")));
        Path out=Path.of(System.getenv("SELECTED_REPAIR_OUTPUT"));Files.createDirectories(out);
        var ai=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        var client=new OpenAiResponsesClient(ai,json);var qa=new StyledSeedQualityAgent(client,ai,json);
        var provider=new StyledPixelLabClient(new AssetProperties(true,false,System.getenv("PIXELLAB_API_KEY"),"sb_secret_unused","dog-photos","dog-assets"),json);
        var photo=Files.readAllBytes(Path.of(System.getenv("RECOVERY_LIVE_PHOTO")));
        var traits=json.readTree(Files.readAllBytes(Path.of(System.getenv("RECOVERY_LIVE_TRAITS"))));
        var seeds=new ArrayList<byte[]>();String stage=System.getenv().getOrDefault("SELECTED_REPAIR_STAGE","original");
        for(String d:StyledSpriteCodec.DIRECTIONS)seeds.add(Files.readAllBytes(Path.of("scripts/fixtures/selected-seed-repair-v16/"+stage+"/"+d+".png")));
        var policy=json.valueToTree(Map.of("recoveryVersion",StyledRecovery.VERSION,"seedRepairVersion",StyledSeedRepair.VERSION,"seedMotionMargin",1));
        JsonNode report=review(out.resolve("initial"),qa,photo,seeds,traits,policy);int submissions=0;
        for(int n=1;n<=3 && !report.path("passed").asBoolean();n++){
            Path dir=out.resolve("attempt-"+n);Files.createDirectories(dir);
            Path planned=dir.resolve("plan.json");JsonNode plan;
            if(Files.exists(planned))plan=json.readTree(Files.readAllBytes(planned));
            else{intent(dir.resolve("plan-intent.txt"),StyledSeedQualityAgent.binding(seeds));plan=StyledSeedRepair.plan(client,json,seeds,report);Files.write(planned,json.writeValueAsBytes(plan));}
            ((ObjectNode)report).set("seedRepairPlan",plan);Files.write(dir.resolve("source-review.json"),json.writeValueAsBytes(report));
            if(!plan.path("status").asText().equals("READY"))break;
            var payload=StyledSeedRepair.payload(json,seeds,report,932000+n);Path request=dir.resolve("request.json"),receipt=dir.resolve("provider-id.txt"),saved=dir.resolve("provider.json");
            UUID id;
            if(Files.exists(receipt)){assertThat(json.readTree(Files.readAllBytes(request))).isEqualTo(payload);id=UUID.fromString(Files.readString(receipt));}
            else{assertThat(Files.exists(request)).as("Unknown paid submission must not be repeated").isFalse();Files.write(request,json.writeValueAsBytes(payload),StandardOpenOption.CREATE_NEW);
                id=StyledSeedRepair.regional(report)?provider.editSeedEyes(payload):provider.editSeeds(payload);submissions++;Files.writeString(receipt,id.toString(),StandardOpenOption.CREATE_NEW);}
            JsonNode result;
            if(Files.exists(saved))result=json.readTree(Files.readAllBytes(saved));
            else{long until=System.nanoTime()+java.time.Duration.ofMinutes(5).toNanos();result=json.createObjectNode();
                while(System.nanoTime()<until){result=StyledSeedRepair.regional(report)?provider.pollSeedEyes(id):provider.pollSeeds(id,StyledSeedRepair.directions(seeds,report));if(!result.path("status").asText().equals("WAITING"))break;Thread.sleep(5000);}
                assertThat(result.path("status").asText()).isEqualTo("COMPLETED");Files.write(saved,json.writeValueAsBytes(result));}
            var repaired=StyledSeedRepair.apply(seeds,report,result);var dirs=StyledSeedRepair.directions(seeds,report);
            for(int i=0;i<4;i++)if(!dirs.contains(StyledSpriteCodec.DIRECTIONS.get(i)))assertThat(repaired.seeds().get(i)).isEqualTo(seeds.get(i));
            Files.write(dir.resolve("preservation.json"),json.writeValueAsBytes(Map.of("directions",dirs,"sourceHashes",seeds.stream().map(StyledSpriteCodec::sha).toList(),"outputHashes",repaired.seeds().stream().map(StyledSpriteCodec::sha).toList(),"changedPixels",repaired.changedPixels(),"rawOutsideMaskDifferences",repaired.outsideMaskDifferences())));
            seeds=new ArrayList<>(repaired.seeds());for(int i=0;i<4;i++)Files.write(dir.resolve(StyledSpriteCodec.DIRECTIONS.get(i)+".png"),seeds.get(i));
            report=review(dir,qa,photo,seeds,traits,policy);
        }
        Files.write(out.resolve("result.json"),json.writeValueAsBytes(Map.of("passed",report.path("passed").asBoolean(),"newPixelLabCalls",submissions,"report",report,"productionPublished",false)));
        assertThat(report.path("passed").asBoolean()).as("Actual image quality; no forced acceptance").isTrue();
    }
    JsonNode review(Path dir,StyledSeedQualityAgent qa,byte[] photo,List<byte[]> seeds,JsonNode traits,JsonNode policy)throws Exception{
        Files.createDirectories(dir);Path reviewed=dir.resolve("review.json");
        if(Files.exists(reviewed)){var r=json.readTree(Files.readAllBytes(reviewed));StyledSeedRepair.verifySource(seeds,r);return r;}
        intent(dir.resolve("review-intent.txt"),StyledSeedQualityAgent.binding(seeds));
        var result=StyledSeedQualityAgent.motionMargin(qa.reviewRecovery(photo,seeds,json.createArrayNode(),traits),seeds,policy,json);
        Files.write(reviewed,json.writeValueAsBytes(result));return result;
    }
    void intent(Path path,String hash)throws Exception{assertThat(Files.exists(path)).as("Unknown paid AI request must not be repeated").isFalse();Files.writeString(path,hash,StandardOpenOption.CREATE_NEW);}
}
