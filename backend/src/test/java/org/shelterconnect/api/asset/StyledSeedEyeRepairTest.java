package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.OpenAiResponsesClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledSeedEyeRepairTest {
    final JsonMapper json=JsonMapper.builder().build();
    List<byte[]> seeds()throws Exception {var r=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)r.add(Files.readAllBytes(Path.of("scripts/fixtures/small-seed-eyes-v14/source/"+d+".png")));return r;}
    JsonNode plan(List<byte[]> seeds) {
        return json.valueToTree(Map.of("version",StyledSeedEyeRepair.VERSION,"sourceBinding",StyledSeedQualityAgent.binding(seeds),
            "rulesSha256",StyledSpriteCodec.qualityRulesSha(),"regions",List.of(Map.of("direction","west","x",5,"y",9,"width",4,"height",5))));
    }
    @Test void actualAutomaticRepairReplaysAllFourNativeViewsWithoutMaskingTheHistoricalAiDisagreement()throws Exception {
        var root=Path.of("scripts/fixtures/small-seed-eyes-v14");var evidence=json.readTree(Files.readAllBytes(root.resolve("evidence.json")));
        for(var entry:evidence.path("sha256").properties())assertThat(StyledSpriteCodec.sha(Files.readAllBytes(root.resolve(entry.getKey())))).isEqualTo(entry.getValue().asText());
        assertThat(evidence.path("baselineAiReviewPassed").asBoolean()).isTrue();
        assertThat(evidence.path("repairTrigger").asText()).isEqualTo("USER_REQUESTED_EYE_EDIT");
        var p=json.readTree(Files.readAllBytes(root.resolve("automatic/plan.json")));
        // This replays pixel preservation, not a new vision approval under later rules.
        ((tools.jackson.databind.node.ObjectNode)p).put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        var result=StyledSeedEyeRepair.apply(seeds(),p,Files.readAllBytes(root.resolve("automatic/raw.png")));
        assertThat(result.changedPixels()).isEqualTo(64);
        for(int i=0;i<4;i++)assertThat(StyledSpriteCodec.nativeFrame(result.seeds().get(i)).getRGB(0,0,32,32,null,0,32))
            .isEqualTo(StyledSpriteCodec.nativeFrame(Files.readAllBytes(root.resolve("automatic/"+StyledSpriteCodec.DIRECTIONS.get(i)+".png"))).getRGB(0,0,32,32,null,0,32));
    }
    @Test void onlySelectedNativePixelsChangeAndPassingViewsRemainByteIdentical()throws Exception {
        var source=seeds();var p=plan(source);var sheet=StyledSeedEyeRepair.strip(source);
        // Real provider semantics: empty background flattened to opaque white.
        for(int y=0;y<32;y++)for(int x=0;x<128;x++)if((sheet.getRGB(x,y)>>>24)==0)sheet.setRGB(x,y,0xffffffff);
        sheet.setRGB(64+7,11,0xffc8b296);
        var result=StyledSeedEyeRepair.apply(source,p,StyledSeedEyeRepair.png(sheet));
        assertThat(result.changedPixels()).isEqualTo(1);
        for(int i:List.of(0,1,3))assertThat(result.seeds().get(i)).isSameAs(source.get(i));
        var before=StyledSpriteCodec.nativeFrame(source.get(2));var after=StyledSpriteCodec.nativeFrame(result.seeds().get(2));
        for(int y=0;y<32;y++)for(int x=0;x<32;x++)if(x!=7 || y!=11)assertThat(after.getRGB(x,y)).isEqualTo(before.getRGB(x,y));
    }
    @Test void changedBodySilhouetteOrInvisibleEyePixelsAreRejectedBeforeQuality()throws Exception {
        var source=seeds();var p=plan(source);
        for(int pixel:List.of(0xffaaaaaa,0)) {
            var sheet=StyledSeedEyeRepair.strip(source);sheet.setRGB(71,11,0xffc8b296);sheet.setRGB(16,20,pixel);
            assertThatThrownBy(()->StyledSeedEyeRepair.apply(source,p,StyledSeedEyeRepair.png(sheet))).hasMessage("SEED_EYE_EDIT_INVALID");
        }
        var transparent=StyledSeedEyeRepair.strip(source);transparent.setRGB(71,11,0);
        assertThatThrownBy(()->StyledSeedEyeRepair.apply(source,p,StyledSeedEyeRepair.png(transparent))).hasMessage("SEED_EYE_EDIT_INVALID");
        var grown=StyledSeedEyeRepair.strip(source);grown.setRGB(71,11,0xffc8b296);grown.setRGB(0,15,0xff111111);
        assertThatThrownBy(()->StyledSeedEyeRepair.apply(source,p,StyledSeedEyeRepair.png(grown))).hasMessage("SEED_EYE_EDIT_INVALID");
        assertThatThrownBy(()->StyledSeedEyeRepair.apply(source,p,StyledSeedEyeRepair.png(StyledSeedEyeRepair.strip(source)))).hasMessage("SEED_EYE_EDIT_UNCHANGED");
    }
    @Test void staleOversizedOverlappingRearAndBodyMasksCannotReachProvider()throws Exception {
        var source=seeds();var agent=new StyledSeedEyeRepair(mock(OpenAiResponsesClient.class),json);
        for(String patch:List.of("{\"direction\":\"north\"}","{\"width\":32}","{\"x\":0}","{\"y\":22}","{\"x\":4294967301}","{\"x\":5.5}")) {
            var p=plan(source).deepCopy();((tools.jackson.databind.node.ObjectNode)p.path("regions").get(0)).setAll((tools.jackson.databind.node.ObjectNode)json.readTree(patch));
            assertThatThrownBy(()->agent.payload(source,p,1)).isInstanceOf(AssetException.class);
        }
        var duplicate=plan(source).deepCopy();((tools.jackson.databind.node.ArrayNode)duplicate.path("regions")).add(duplicate.path("regions").get(0).deepCopy());
        assertThatThrownBy(()->agent.payload(source,duplicate,1)).isInstanceOf(AssetException.class);
        var stale=plan(source).deepCopy();((tools.jackson.databind.node.ObjectNode)stale).put("sourceBinding","0".repeat(64));
        assertThatThrownBy(()->agent.payload(source,stale,1)).isInstanceOf(AssetException.class);
        ((tools.jackson.databind.node.ObjectNode)stale).put("sourceBinding",StyledSeedQualityAgent.binding(source)).put("rulesSha256","0".repeat(64));
        assertThatThrownBy(()->agent.payload(source,stale,1)).isInstanceOf(AssetException.class);
    }
    @Test void uncertainLocatorNeverProducesAnEditAndClippingIsNotAnEyeRepair()throws Exception {
        var source=seeds();var ai=mock(OpenAiResponsesClient.class);var agent=new StyledSeedEyeRepair(ai,json);
        var report=json.valueToTree(Map.of("passed",false,"identity","PASS","issues",List.of("EYE_READABILITY"),"minimumClearPixels",2,"marginDirections",List.of(),
            "inputSha256",StyledSeedQualityAgent.binding(source),"rulesSha256",StyledSpriteCodec.qualityRulesSha(),
            "views",List.of(Map.of("direction","west","readability","FAIL","style","PASS"))));
        when(ai.structuredImage(anyString(),anyString(),any(),anyMap())).thenReturn(json.readTree("{\"confident\":false,\"regions\":[],\"note\":\"uncertain\"}"));
        assertThat(agent.locate(source,report).path("status").asText()).isEqualTo("UNCERTAIN");
        ((tools.jackson.databind.node.ObjectNode)report).set("issues",json.valueToTree(List.of("EYE_READABILITY","SEED_MOTION_MARGIN")));
        assertThat(StyledSeedEyeRepair.failedViews(report)).isEmpty();
    }
}
