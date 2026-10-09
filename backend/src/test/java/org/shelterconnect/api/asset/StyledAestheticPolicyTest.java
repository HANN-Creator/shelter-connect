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

class StyledAestheticPolicyTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Path root=Path.of("scripts/fixtures/tail-spine-v31");
    ObjectNode archived()throws Exception{return (ObjectNode)json.readTree(Files.readAllBytes(root.resolve("v31-actual-full-review.json")));}
    ObjectNode apply(ObjectNode original){
        var r=original.deepCopy();var p=StyledAestheticPolicy.base(json,original.path("propertyReview"));r.set("propertyReview",p);
        r.put("aestheticPolicy",StyledAestheticPolicy.VERSION);r.set("qualityWarnings",p.path("qualityWarnings"));
        r.put("rulesSha256",StyledSpriteCodec.qualityRulesSha());return r;
    }
    List<byte[]> seeds()throws Exception{var a=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)a.add(Files.readAllBytes(root.resolve(d+".png")));return a;}
    @Test void actualMixedCoatReceiptWarnsOnFrontAndRepairsOnlyConfirmedSideDefects()throws Exception {
        var original=archived();var before=json.writeValueAsString(original);var r=apply(original);var source=seeds();
        assertThat(StyledCoatReview.unresolved(original)).isTrue();assertThat(StyledCoatReview.unresolved(r)).isFalse();
        assertThat(r.path("qualityWarnings").toString()).contains("south","COAT_APPEARANCE_UNCERTAIN");
        assertThat(StyledSeedRepair.targets(r)).containsExactly("west","east");var client=mock(OpenAiResponsesClient.class);
        r.set("seedRepairPlan",StyledSeedRepair.plan(client,json,source,r));assertThat(r.at("/seedRepairPlan/status").asText()).isEqualTo("READY");
        var payload=StyledSeedRepair.payload(json,source,r,123);assertThat(payload.path("edit_images").size()).isEqualTo(2);
        assertThat(payload.path("description").asText()).contains("west: COAT_MISMATCH","east: COAT_MISMATCH").doesNotContain("south:");
        assertThat(payload.path("description").asText().length()).isLessThanOrEqualTo(2000);verifyNoInteractions(client);
        var provider=json.createObjectNode().putObject("directions");provider.put("west",Base64.getEncoder().encodeToString(source.get(2)));provider.put("east",Base64.getEncoder().encodeToString(source.get(3)));
        var response=json.createObjectNode();response.set("directions",provider);var applied=StyledSeedRepair.apply(source,r,response);
        assertThat(applied.seeds().get(0)).isSameAs(source.get(0));assertThat(applied.seeds().get(1)).isSameAs(source.get(1));
        assertThat(json.writeValueAsString(original)).isEqualTo(before); // replay changes neither the historical receipt nor source bytes
    }
    @Test void styleWarningCannotRemoveUnreadableEyesWrongDirectionOrMissingTail()throws Exception {
        var r=archived();var v=(ObjectNode)r.at("/propertyReview/views/0");v.put("styleMatches",false).put("eyesReadable",false).put("directionCorrect",false).put("tailPlausible",false);
        v.set("issues",json.valueToTree(List.of("STYLE_DRIFT","EYE_READABILITY","EYE_DIRECTION","TAIL_MISSING")));
        var p=StyledAestheticPolicy.base(json,r.path("propertyReview"));var result=p.at("/views/0");
        assertThat(result.path("styleMatches").asBoolean()).isTrue();assertThat(result.path("eyesReadable").asBoolean()).isFalse();
        assertThat(result.path("directionCorrect").asBoolean()).isFalse();assertThat(result.path("tailPlausible").asBoolean()).isFalse();
        assertThat(result.path("issues").toString()).doesNotContain("STYLE_DRIFT").contains("EYE_READABILITY","EYE_DIRECTION","TAIL_MISSING");
        v.remove("styleMatches");assertThatThrownBy(()->StyledAestheticPolicy.base(json,r.path("propertyReview"))).isInstanceOf(AssetException.class);
    }
    @Test void confidenceAloneDoesNotInventIdentityDefectButConcreteFailuresRemain()throws Exception {
        var r=archived();var v=(ObjectNode)r.at("/propertyReview/views/0");v.putArray("issues");v.put("confidence",.6);
        var p=StyledAestheticPolicy.base(json,r.path("propertyReview"));assertThat(StyledAestheticPolicy.lowConfidenceBlocks(p,p.at("/views/0"))).isFalse();
        v.put("eyesReadable",false);p=StyledAestheticPolicy.base(json,r.path("propertyReview"));assertThat(StyledAestheticPolicy.lowConfidenceBlocks(p,p.at("/views/0"))).isTrue();
    }
    @Test void warningsCannotEnterWholeImageLearningButEmptyAndLegacyReportsCan() {
        assertThat(StyledAestheticPolicy.hasWarnings(json.createObjectNode())).isFalse();
        var report=json.createObjectNode();report.putArray("qualityWarnings");assertThat(StyledAestheticPolicy.hasWarnings(report)).isFalse();
        report.withObject("rawEditReview").putArray("qualityWarnings").addObject().put("code","PALETTE_UNCERTAIN");
        assertThat(StyledAestheticPolicy.hasWarnings(report)).isTrue();
        var jdbc=mock(org.springframework.jdbc.core.simple.JdbcClient.class);var assets=mock(AssetStore.class);var accounts=mock(org.shelterconnect.api.auth.AccountService.class);
        var store=new StyledLessonStore(jdbc,json,assets,accounts,new org.shelterconnect.api.chat.AiProperties(false,"","test",60));
        var work=mock(StyledAssetStore.Work.class);when(work.qualityPolicy()).thenReturn(json.createObjectNode());report.put("passed",false);
        store.record(work,report,json.createObjectNode(),json.createObjectNode());verifyNoInteractions(jdbc,assets,accounts);
    }
    @Test void maximumEvidenceQuotesCannotOverflowProviderPromptOrDropDefectCodes()throws Exception {
        var r=apply(archived());for(var n:r.at("/propertyReview/views")){var v=(ObjectNode)n;v.set("issues",json.valueToTree(List.of("COAT_MISMATCH")));v.put("coatRepairScope","BODY");v.withObject("coatObservation").put("photoPattern","x".repeat(400));}
        r.set("seedRepairPlan",StyledSeedRepair.plan(mock(OpenAiResponsesClient.class),json,seeds(),r));var p=StyledSeedRepair.payload(json,seeds(),r,123);
        assertThat(p.path("description").asText().length()).isLessThanOrEqualTo(2000);
        for(String d:StyledSpriteCodec.DIRECTIONS)assertThat(p.path("description").asText()).contains(d+": COAT_MISMATCH");
    }
    @Test void ambiguousPaletteWarnsAndAllowsRawWhileConfirmedFlickerAndAnatomyStillBlock()throws Exception {
        var fixture=new StyledMotionReviewTest();var client=mock(OpenAiResponsesClient.class);
        when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenReturn(fixture.observation("palette","UNCERTAIN"));
        var r=fixture.run(client,"idle-west.png","IDLE");assertThat(r.path("passed").asBoolean()).isTrue();assertThat(r.path("qualityWarnings").toString()).contains("PALETTE_UNCERTAIN");
        assertThat(StyledRawMotion.genuinePass(r,"IDLE")).isTrue();verify(client,times(1)).structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"));
        when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenReturn(fixture.observation("palette","FAIL"));
        r=fixture.run(client,"idle-west.png","IDLE");assertThat(r.path("passed").asBoolean()).isFalse();assertThat(r.path("issues").toString()).contains("IDENTITY_DRIFT");
        var mixed=fixture.observation("palette","UNCERTAIN");((ObjectNode)StyledMotionReview.property(mixed,"tail")).put("state","UNCERTAIN");
        when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenReturn(mixed);
        r=fixture.run(client,"idle-west.png","IDLE");assertThat(r.path("passed").asBoolean()).isFalse();assertThat(r.path("uncertainProperties").toString()).isEqualTo("[\"tail\"]");
        assertThat(StyledRawMotion.genuinePass(r,"IDLE")).isFalse();
    }
}
