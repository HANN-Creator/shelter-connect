package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.OpenAiResponsesClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledSeedRepairTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Path root=Path.of("scripts/fixtures/selected-seed-repair-v16");
    List<byte[]> seeds(String stage)throws Exception{var result=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)result.add(Files.readAllBytes(root.resolve(stage+"/"+d+".png")));return result;}
    ObjectNode report(String stage)throws Exception{
        var r=(ObjectNode)json.readTree(Files.readAllBytes(root.resolve("reports.json"))).path(stage).deepCopy();
        // Replay historical selection/preservation only. This is not a new v16 visual approval.
        r.put("rulesSha256",StyledSpriteCodec.qualityRulesSha());return r;
    }
    @Test void actualHostedFailureSelectsOnlyTheOffendingViewAndLeavesThreePassingPngsUntouched()throws Exception{
        var source=seeds("original");var report=report("original");
        assertThat(StyledSeedRepair.targets(report)).containsExactly("north");
        var client=mock(OpenAiResponsesClient.class);var plan=StyledSeedRepair.plan(client,json,source,report);report.set("seedRepairPlan",plan);
        verifyNoInteractions(client);var payload=StyledSeedRepair.payload(json,source,report,123);
        assertThat(payload.path("edit_images").size()).isEqualTo(1);
        assertThat(Base64.getDecoder().decode(payload.at("/edit_images/0/image/base64").asText())).isEqualTo(source.get(1));
        var edited=StyledSpriteCodec.nativeFrame(source.get(1));edited.setRGB(16,20,0xffaabb00);byte[] next=StyledSpriteCodec.png(edited);
        var result=StyledSeedRepair.apply(source,report,json.valueToTree(Map.of("directions",Map.of("north",Base64.getEncoder().encodeToString(next)))));
        assertThat(result.seeds().get(1)).isEqualTo(next);
        for(int i:List.of(0,2,3))assertThat(result.seeds().get(i)).isSameAs(source.get(i));
        assertThatThrownBy(()->StyledSeedRepair.apply(source,report,json.valueToTree(Map.of("directions",Map.of("south",Base64.getEncoder().encodeToString(next)))))).hasMessage("SEED_REPAIR_SELECTION_INVALID");
    }
    @Test void regionalMouthRepairPreservesAllOtherPixelsEvenIfInpaintChangesUnselectedContext()throws Exception{
        var source=seeds("final");var report=report("final");assertThat(StyledSeedRepair.targets(report)).containsExactly("south");
        var client=mock(OpenAiResponsesClient.class);
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"confident\":true,\"regions\":[{\"direction\":\"south\",\"x\":14,\"y\":19,\"width\":3,\"height\":1}],\"note\":\"fixture, not vision\"}"));
        report.set("seedRepairPlan",StyledSeedRepair.plan(client,json,source,report));
        assertThat(report.at("/seedRepairPlan/status").asText()).isEqualTo("READY");
        var payload=StyledSeedRepair.payload(json,source,report,123);assertThat(payload.has("edit_images")).isFalse();
        var raw=StyledSeedEyeRepair.strip(source);raw.setRGB(15,19,0xffbbaa99);raw.setRGB(90,25,0xffaabbcc);
        var result=StyledSeedRepair.apply(source,report,json.valueToTree(Map.of("eyeSheet",Base64.getEncoder().encodeToString(StyledSeedEyeRepair.png(raw)))));
        assertThat(result.changedPixels()).isEqualTo(1);assertThat(result.outsideMaskDifferences()).isEqualTo(1);
        for(int i=1;i<4;i++)assertThat(result.seeds().get(i)).isSameAs(source.get(i));
        var before=StyledSpriteCodec.nativeFrame(source.getFirst());var after=StyledSpriteCodec.nativeFrame(result.seeds().getFirst());
        for(int y=0;y<32;y++)for(int x=0;x<32;x++)if(x!=15 || y!=19)assertThat(after.getRGB(x,y)).isEqualTo(before.getRGB(x,y));
    }
    @Test void facialCoatMaskCannotReplaceRearTailAndKeepsEveryOutsidePixel()throws Exception {
        var source=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)source.add(Files.readAllBytes(Path.of("scripts/fixtures/material-coat-v25/"+d+".png")));
        var r=(ObjectNode)json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/material-coat-v25/deployed-review.json")));
        r.put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        for(var n:r.at("/propertyReview/views")){var v=(ObjectNode)n;v.set("issues",json.createArrayNode());
            if(v.path("direction").asText().equals("south")){v.put("coatRepairScope","FACE");v.set("issues",json.valueToTree(List.of("COAT_MISMATCH")));}}
        var client=mock(OpenAiResponsesClient.class);
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"confident\":true,\"regions\":[{\"direction\":\"south\",\"x\":14,\"y\":10,\"width\":2,\"height\":2}],\"note\":\"synthetic position test\"}"));
        r.set("seedRepairPlan",StyledSeedRepair.plan(client,json,source,r));
        assertThat(r.at("/seedRepairPlan/method").asText()).isEqualTo("REGION");assertThat(r.at("/seedRepairPlan/status").asText()).isEqualTo("READY");
        var raw=StyledSeedEyeRepair.strip(source);raw.setRGB(14,10,0xffab8877);raw.setRGB(90,20,0xffabcdef);
        var repaired=StyledSeedRepair.apply(source,r,json.valueToTree(Map.of("eyeSheet",Base64.getEncoder().encodeToString(StyledSeedEyeRepair.png(raw)))));
        for(int i=1;i<4;i++)assertThat(repaired.seeds().get(i)).isSameAs(source.get(i));
        var before=StyledSpriteCodec.nativeFrame(source.get(0));var after=StyledSpriteCodec.nativeFrame(repaired.seeds().get(0));
        for(int y=0;y<32;y++)for(int x=0;x<32;x++)if(!(x>=14 && x<16 && y>=10 && y<12))assertThat(after.getRGB(x,y)).isEqualTo(before.getRGB(x,y));
        var plan=(ObjectNode)r.path("seedRepairPlan");plan.set("directions",json.valueToTree(List.of("west")));
        plan.set("regions",json.readTree("[{\"direction\":\"west\",\"x\":24,\"y\":12,\"width\":2,\"height\":2}]"));
        assertThatThrownBy(()->StyledSeedRepair.mask(source,plan)).isInstanceOf(AssetException.class);
    }
    @Test void ambiguousGlobalFailureAndUncertainRegionsNeverBecomePaidFullRedraws()throws Exception{
        var source=seeds("final");var r=report("final");var client=mock(OpenAiResponsesClient.class);
        when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"confident\":false,\"regions\":[],\"note\":\"uncertain white fur\"}"));
        r.set("seedRepairPlan",StyledSeedRepair.plan(client,json,source,r));assertThat(r.at("/seedRepairPlan/status").asText()).isEqualTo("UNCERTAIN");
        assertThatThrownBy(()->StyledSeedRepair.payload(json,source,r,123)).hasMessage("SEED_REPAIR_SELECTION_INVALID");
        for(var v:r.at("/propertyReview/views"))((ObjectNode)v).set("issues",json.createArrayNode());
        ((ObjectNode)r.path("propertyReview")).put("tailConsistent",false);
        assertThatThrownBy(()->StyledSeedRepair.targets(r)).hasMessage("SEED_REPAIR_SELECTION_INVALID");
    }
    @Test void realThreeEditCanaryRetainsAllPassingHashesAndFinalNativeFrames()throws Exception{
        var evidence=json.readTree(Files.readAllBytes(root.resolve("live-evidence.json")));
        assertThat(evidence.path("newPixelLabCalls").asInt()).isEqualTo(3);
        var source=seeds("original");var finalSeeds=seeds("selected");var previous=source.stream().map(StyledSpriteCodec::sha).toList();
        for(var attempt:evidence.path("attempts")){
            var p=attempt.path("preservation");var dirs=p.path("directions").valueStream().map(JsonNode::asText).toList();
            assertThat(p.path("sourceHashes").valueStream().map(JsonNode::asText).toList()).isEqualTo(previous);
            for(int i=0;i<4;i++)if(!dirs.contains(StyledSpriteCodec.DIRECTIONS.get(i)))assertThat(p.path("outputHashes").get(i)).isEqualTo(p.path("sourceHashes").get(i));
            previous=p.path("outputHashes").valueStream().map(JsonNode::asText).toList();
        }
        assertThat(finalSeeds.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(previous);
        assertThat(finalSeeds.getFirst()).isEqualTo(source.getFirst());
        assertThat(evidence.path("finalReport").path("passed").asBoolean()).isTrue();
        assertThat(evidence.path("finalReport").path("inputSha256").asText()).isEqualTo(StyledSeedQualityAgent.binding(finalSeeds));
        for(byte[] frame:finalSeeds)assertThat(StyledQualityAgent.touchesEdge(StyledSpriteCodec.nativeFrame(frame))).isFalse();
    }
    @Test void lowConfidenceDoesNotPurchaseAnArbitraryReplacement()throws Exception{
        var source=seeds("original");var report=report("original");var client=mock(OpenAiResponsesClient.class);
        ((ObjectNode)report.at("/propertyReview/views").get(1)).put("confidence",.6);
        report.set("seedRepairPlan",StyledSeedRepair.plan(client,json,source,report));
        assertThat(report.at("/seedRepairPlan/status").asText()).isEqualTo("UNCERTAIN_VERDICT");
        assertThatThrownBy(()->StyledSeedRepair.payload(json,source,report,123)).hasMessage("SEED_REPAIR_SELECTION_INVALID");verifyNoInteractions(client);
    }
    @Test void staleBindingsPassingViewMasksAndOutOfBoundsMasksAreRejected()throws Exception{
        var source=seeds("final");var r=report("final");var client=mock(OpenAiResponsesClient.class);
        for(String region:List.of("{\"direction\":\"north\",\"x\":14,\"y\":19,\"width\":3,\"height\":1}","{\"direction\":\"south\",\"x\":0,\"y\":0,\"width\":3,\"height\":1}","{\"direction\":\"south\",\"x\":14,\"y\":27,\"width\":3,\"height\":1}")){
            when(client.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"confident\":true,\"regions\":["+region+"],\"note\":\"invalid\"}"));
            r.set("seedRepairPlan",StyledSeedRepair.plan(client,json,source,r));
            assertThat(r.at("/seedRepairPlan/status").asText()).isEqualTo("INVALID_REGIONS");
            assertThatThrownBy(()->StyledSeedRepair.payload(json,source,r,123)).hasMessage("SEED_REPAIR_SELECTION_INVALID");
        }
        r.put("inputSha256","0".repeat(64));assertThatThrownBy(()->StyledSeedRepair.plan(client,json,source,r)).hasMessage("SEED_REPAIR_SELECTION_INVALID");
    }
}
