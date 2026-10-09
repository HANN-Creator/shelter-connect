package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class StyledCoatIdentityTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Path root=Path.of("scripts/fixtures/coat-identity-v33");
    ObjectNode archived()throws Exception{return (ObjectNode)json.readTree(Files.readAllBytes(root.resolve("deployed-review.json")));}
    ObjectNode observed(ObjectNode receipt,String identity,String coat) {
        var observed=(ObjectNode)receipt.at("/coatEvidence/observation").deepCopy();
        var view=(ObjectNode)observed.at("/views/0");view.put("decision",coat);
        view.putObject("nonCoatIdentity").put("decision",identity).put("confidence",.95)
            .put("evidence","Synthetic regression evidence, not a live observation: corresponding ears, muzzle and proportions.");
        return observed;
    }
    ObjectNode resolve(ObjectNode receipt,ObjectNode observed) {
        return StyledAestheticPolicy.base(json,StyledCoatReview.resolve(json,receipt.at("/coatEvidence/originalPropertyReview"),observed,List.of("south")));
    }
    @Test void actualDeployedCoatOnlyFalseFlagNeedsIndependentNonCoatEvidence()throws Exception {
        var receipt=archived();var unchanged=json.writeValueAsString(receipt);
        var evidence=json.readTree(Files.readAllBytes(root.resolve("evidence.json")));
        assertThat(StyledSpriteCodec.sha(Files.readAllBytes(root.resolve("deployed-review.json")))).isEqualTo(evidence.path("reviewSha256").asText());
        assertThat(receipt.path("issues").toString()).isEqualTo("[\"SEED_IDENTITY\"]");
        assertThat(receipt.at("/propertyReview/views/0/issues").isEmpty()).isTrue();
        assertThat(receipt.at("/propertyReview/views/0/identityMatches").asBoolean()).isFalse();
        var p=resolve(receipt,observed(receipt,"PRESERVED","PRESERVED"));
        assertThat(p.at("/views/0/identityMatches").asBoolean()).isTrue();
        assertThat(p.at("/views/0/originalIdentityMatches").asBoolean()).isFalse();
        assertThat(p.at("/views/0/identityResolution").asText()).isEqualTo("INDEPENDENT_NON_COAT_PRESERVED");
        assertThat(p.path("qualityWarnings").toString()).contains("COAT_APPEARANCE_UNCERTAIN");
        for(var v:p.path("views")) {assertThat(v.path("issues").isEmpty()).isTrue();for(String f:StyledRecoveryReview.BASE.keySet())assertThat(v.path(f).asBoolean()).as(f).isTrue();}
        assertThat(json.writeValueAsString(receipt)).isEqualTo(unchanged);
    }
    @Test void explicitIndependentIdentityDefectAndUnknownAnatomyStayBlocked()throws Exception {
        for(String decision:List.of("MISMATCH","UNCERTAIN")) {
            var receipt=archived();var p=resolve(receipt,observed(receipt,decision,"PRESERVED"));
            assertThat(p.at("/views/0/identityMatches").asBoolean()).isFalse();
            var report=json.createObjectNode().put("aestheticPolicy",StyledAestheticPolicy.VERSION);report.set("propertyReview",p);
            assertThat(StyledCoatReview.unresolved(report)).isEqualTo(decision.equals("UNCERTAIN"));
        }
        var receipt=archived();((ObjectNode)receipt.at("/coatEvidence/originalPropertyReview/views/0")).withArray("issues").add("SEED_IDENTITY");
        var p=resolve(receipt,observed(receipt,"PRESERVED","PRESERVED"));
        assertThat(p.at("/views/0/identityMatches").asBoolean()).isFalse();
        assertThat(p.at("/views/0/issues").toString()).contains("SEED_IDENTITY");
        assertThat(p.at("/views/0/identityObservationUncertain").asBoolean()).isTrue();
    }
    @Test void independentNormalIdentityDoesNotRemoveConfirmedCoatLossOrOtherDefects()throws Exception {
        var r=archived();var v=(ObjectNode)r.at("/coatEvidence/originalPropertyReview/views/0");
        v.put("eyesReadable",false).put("directionCorrect",false).put("tailPlausible",false);
        v.withArray("issues").add("EYE_READABILITY").add("EYE_DIRECTION").add("TAIL_MISSING").add("CANVAS_CLIPPING");
        var p=resolve(r,observed(r,"PRESERVED","FACE_PATTERN_MISSING"));var result=p.at("/views/0");
        assertThat(result.path("identityMatches").asBoolean()).isTrue();
        assertThat(result.path("issues").toString()).contains("COAT_MISMATCH","EYE_READABILITY","EYE_DIRECTION","TAIL_MISSING","CANVAS_CLIPPING");
        assertThat(result.path("coatRepairScope").asText()).isEqualTo("FACE");
        for(String field:List.of("eyesReadable","directionCorrect","tailPlausible"))assertThat(result.path(field).asBoolean()).isFalse();
        p=resolve(r,observed(r,"PRESERVED","PRESERVED"));
        var report=json.createObjectNode();report.set("propertyReview",p);
        assertThat(StyledAestheticPolicy.repairDescription(report,List.of("south"))).doesNotContain("Non-coat identity evidence:");
    }
    @Test void missingMalformedOrLowConfidenceEvidenceCannotClearIdentity()throws Exception {
        var r=archived();var obs=observed(r,"PRESERVED","PRESERVED");
        ((ObjectNode)obs.at("/views/0/nonCoatIdentity")).put("confidence",.5);
        assertThat(resolve(r,obs).at("/views/0/identityMatches").asBoolean()).isFalse();
        for(String key:List.of("decision","confidence","evidence")) {
            var invalid=observed(r,"PRESERVED","PRESERVED");((ObjectNode)invalid.at("/views/0/nonCoatIdentity")).remove(key);
            assertThatThrownBy(()->resolve(r,invalid)).isInstanceOf(AssetException.class);
        }
        var invalid=observed(r,"PRESERVED","PRESERVED");((ObjectNode)invalid.at("/views/0")).remove("nonCoatIdentity");
        assertThatThrownBy(()->resolve(r,invalid)).isInstanceOf(AssetException.class);
    }
    @Test void styleCodeAliasesCannotRestoreACosmeticBlockButUnreadableEyesStillBlock()throws Exception {
        var r=archived();var general=(ObjectNode)r.at("/coatEvidence/originalPropertyReview");var view=(ObjectNode)general.at("/views/0");
        view.put("identityMatches",true).put("styleMatches",false).put("eyesReadable",false);
        view.set("issues",json.valueToTree(List.of("EYE_STYLE","STYLE_DRIFT","EYE_READABILITY")));
        var p=StyledAestheticPolicy.base(json,general);
        assertThat(p.at("/views/0/issues").toString()).isEqualTo("[\"EYE_READABILITY\"]");
        assertThat(p.at("/views/0/eyesReadable").asBoolean()).isFalse();assertThat(p.at("/views/0/styleMatches").asBoolean()).isTrue();
    }
    @Test void productionReviewerAggregatesTheIndependentIdentityVerdictAndPreservesRawFailure()throws Exception {
        var seeds=CoatIdentityReplay.seeds();
        for(String decision:List.of("PRESERVED","MISMATCH","UNCERTAIN")) {
            var observation=CoatIdentityReplay.syntheticObservation(json,decision);
            var r=CoatIdentityReplay.review(json,seeds.get(0),seeds,json.createObjectNode(),observation);
            assertThat(r.path("passed").asBoolean()).isEqualTo(decision.equals("PRESERVED"));
            assertThat(r.path("issues").toString()).isEqualTo(decision.equals("PRESERVED")?"[]":"[\"SEED_IDENTITY\"]");
            assertThat(r.at("/coatEvidence/originalPropertyReview/views/0/identityMatches").asBoolean()).isFalse();
            assertThat(StyledCoatReview.unresolved(r)).isEqualTo(decision.equals("UNCERTAIN"));
            assertThat(StyledTailAnatomy.boundPass(r)).isTrue();
            if(decision.equals("MISMATCH"))assertThat(StyledAestheticPolicy.repairDescription(r,List.of("south")))
                .contains("Non-coat identity evidence",observation.at("/views/0/nonCoatIdentity/evidence").asText()).doesNotContain("얼굴");
        }
    }
    @Test void recordedLiveIndependentObservationIsBoundToTheSameOriginals()throws Exception {
        var evidence=json.readTree(Files.readAllBytes(root.resolve("evidence.json"))).path("independentObservation");
        var bytes=Files.readAllBytes(root.resolve(evidence.path("file").asText()));
        assertThat(StyledSpriteCodec.sha(bytes)).isEqualTo(evidence.path("sha256").asText());
        var seeds=CoatIdentityReplay.seeds();
        assertThat(StyledSeedQualityAgent.binding(seeds)).isEqualTo(evidence.at("/binding/inputSha256").asText());
        assertThat(StyledSpriteCodec.sha(Files.readAllBytes(root.resolve("deployed-review.json")))).isEqualTo(evidence.at("/binding/originalReportSha256").asText());
        var report=CoatIdentityReplay.review(json,seeds.get(0),seeds,json.createObjectNode(),json.readTree(bytes));
        assertThat(report.path("passed").asBoolean()).isTrue();
        assertThat(report.path("qualityWarnings").size()).isEqualTo(1);
    }
}
