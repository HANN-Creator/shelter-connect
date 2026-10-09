package org.shelterconnect.api.asset;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledTailAnatomyTest {
    final JsonMapper json=JsonMapper.builder().build();
    final AiProperties ai=new AiProperties(true,"fixture-key","gpt-5.6-luna",30);
    List<byte[]> seeds(){return fixture("case-a");}
    static List<byte[]> fixture(String name){try{var result=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)result.add(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("scripts/fixtures/tail-anatomy-v21",name,d+".png")));return result;}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    ObjectNode raw(String west,String east){
        var out=json.createObjectNode().put("photoTail","OBSCURED").put("photoEvidence","Photograph hides the rump and tail.");var views=out.putArray("views");
        for(String d:List.of("west","east")){
            String tail=d.equals("west")?west:east;boolean missing=tail.equals("NOT_DISCERNIBLE"),unknown=tail.equals("UNCERTAIN");
            views.addObject().put("direction",d).put("visibleEvidence",missing?"No projection from rump, no distinct contour or tip.":"A distinct curved tail joins the rump and ends inside the canvas.")
                .put("tail",tail).put("attachment",unknown?"UNCERTAIN":missing?"NOT_VISIBLE":"CONNECTED")
                .put("contour",unknown?"UNCERTAIN":missing?"ABSENT":"DISTINCT").put("tip",unknown?"UNCERTAIN":missing?"NOT_VISIBLE":"VISIBLE");
        }return out;
    }
    ObjectNode assess(JsonNode raw){return assess(raw,seeds());}
    ObjectNode assess(JsonNode raw,List<byte[]> pixels){return StyledTailAnatomy.assess(json,raw,StyledTailGeometry.measure(json,pixels),List.of("west","east"));}
    ObjectNode report(){
        var r=json.createObjectNode().put("passed",true).put("repairDescription","").put("inputSha256",StyledSeedQualityAgent.binding(seeds()))
            .put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("recoveryVersion",StyledRecovery.VERSION);
        r.putArray("issues");var p=r.putObject("propertyReview").put("tailConsistent",true);var views=p.putArray("views");
        for(String d:StyledSpriteCodec.DIRECTIONS){var v=views.addObject().put("direction",d).put("confidence",.95);v.putArray("issues");StyledRecoveryReview.BASE.keySet().forEach(k->v.put(k,true));}return r;
    }
    @Test void categoricalCompleteTailDoesNotRequireCoordinatesOrASelfConfidenceThreshold(){
        var raw=raw("COMPLETE_CONNECTED","COMPLETE_CONNECTED");
        assertThat(assess(raw).path("decision").asText()).isEqualTo("PASS");
        ((ObjectNode)raw.at("/views/0")).put("confidence",.1).set("tailPixelPath",json.createArrayNode().addObject().put("x",0).put("y",0));
        assertThat(assess(raw).path("passed").asBoolean()).isTrue();
        assertThat(json.writeValueAsString(StyledTailAnatomy.schema(List.of("west","east")))).doesNotContain("confidence","tailPixelPath");
    }
    @Test void absentTailRemainsDefectEvenWhenPhotoHidesIt(){
        var r=assess(raw("NOT_DISCERNIBLE","COMPLETE_CONNECTED"),fixture("case-b"));
        assertThat(r.path("decision").asText()).isEqualTo("CONFIRMED_DEFECT");assertThat(r.path("failedDirections").toString()).isEqualTo("[\"west\"]");
        assertThat(r.path("uncertainDirections").isEmpty()).isTrue();
    }
    @Test void unknownAndContradictoryAnatomyCannotPassOrAuthorizeRepair(){
        var uncertain=assess(raw("UNCERTAIN","COMPLETE_CONNECTED"));var r=report();StyledTailAnatomy.merge(json,r,uncertain);
        assertThat(r.path("qualityDecision").asText()).isEqualTo("UNCERTAIN");assertThat(r.path("issues").isEmpty()).isTrue();
        assertThat(StyledTailAnatomy.unresolved(r)).isTrue();assertThat(r.path("repairDescription").asText()).isEmpty();
        assertThat(StyledSeedRepair.plan(mock(OpenAiResponsesClient.class),json,seeds(),r).path("status").asText()).isEqualTo("UNCERTAIN_VERDICT");
        var contradiction=raw("COMPLETE_CONNECTED","COMPLETE_CONNECTED");((ObjectNode)contradiction.at("/views/0")).put("attachment","NOT_VISIBLE");
        assertThat(assess(contradiction).path("decision").asText()).isEqualTo("UNCERTAIN");
    }
    @Test void unresolvedTailCannotBeRedrawnFromGeneralTailFlagsButIndependentEyeDefectsStillCan(){
        for(boolean eyeDefect:List.of(false,true)){
            var r=report();r.put("passed",false).put("repairDescription","Redraw the unconfirmed missing tail, and fix the eye.");((ObjectNode)r.path("propertyReview")).put("tailConsistent",false);
            var view=(ObjectNode)r.at("/propertyReview/views/2");view.put("tailPlausible",false);var codes=view.putArray("issues").add("TAIL_MISSING").add("TAIL_CARRIAGE");
            r.putArray("issues").add("SEED_IDENTITY");
            if(eyeDefect){view.put("eyesReadable",false);codes.add("EYE_READABILITY");((tools.jackson.databind.node.ArrayNode)r.path("issues")).add("EYE_READABILITY");}
            StyledTailAnatomy.merge(json,r,assess(raw("UNCERTAIN","COMPLETE_CONNECTED")));
            assertThat(r.at("/generalPropertyReview/views/2/issues").toString()).contains("TAIL_MISSING");
            assertThat(r.path("repairDescription").asText()).doesNotContain("Redraw the unconfirmed");
            if(eyeDefect)assertThat(r.path("repairDescription").asText()).contains("EYE_READABILITY","Preserve the original tail pixels");
            var plan=StyledSeedRepair.plan(mock(OpenAiResponsesClient.class),json,seeds(),r);
            assertThat(plan.path("directions").valueStream().map(JsonNode::asText).toList()).isEqualTo(eyeDefect?List.of("west"):List.of());
            assertThat(plan.path("status").asText()).isEqualTo(eyeDefect?"READY":"UNCERTAIN_VERDICT");
            if(!eyeDefect)assertThat(r.path("qualityDecision").asText()).isEqualTo("UNCERTAIN");
        }
    }
    @Test void exactGeometryCannotBeWaivedByAVisionPass(){
        var geometry=(ObjectNode)StyledTailGeometry.measure(json,seeds());
        ((ObjectNode)geometry.path("west")).put("edgeContact",true);
        var result=StyledTailAnatomy.assess(json,raw("COMPLETE_CONNECTED","COMPLETE_CONNECTED"),geometry,List.of("west","east"));
        assertThat(result.at("/verdicts/west/issue").asText()).isEqualTo("CANVAS_CLIPPING");
        ((ObjectNode)geometry.path("west")).put("edgeContact",false).put("opaqueComponents",2);
        assertThat(StyledTailAnatomy.assess(json,raw("COMPLETE_CONNECTED","COMPLETE_CONNECTED"),geometry,List.of("west","east")).path("passed").asBoolean()).isFalse();
        var detached=raw("DETACHED_OR_CROPPED","COMPLETE_CONNECTED");((ObjectNode)detached.at("/views/0")).put("attachment","DETACHED");
        assertThat(StyledTailAnatomy.assess(json,detached,geometry,List.of("west","east")).path("decision").asText()).isEqualTo("CONFIRMED_DEFECT");
    }
    @Test void shortTailExceptionRequiresDirectPhotoEvidence(){
        var observation=raw("SHORT_STUB","SHORT_STUB");assertThat(assess(observation).path("passed").asBoolean()).isFalse();
        observation.put("photoTail","VISIBLE_NATURALLY_SHORT");assertThat(assess(observation).path("passed").asBoolean()).isTrue();
    }
    @Test void unresolvedReviewGetsOnlyOneIndependentCallWithNoPreviousConclusion(){
        for(String outcome:List.of("resolved","unknown","timeout","invalid")){
            var client=mock(OpenAiResponsesClient.class);var setup=when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenReturn(raw("UNCERTAIN","COMPLETE_CONNECTED"));
            var followup=raw(outcome.equals("resolved")?"COMPLETE_CONNECTED":"UNCERTAIN","COMPLETE_CONNECTED");((tools.jackson.databind.node.ArrayNode)followup.path("views")).remove(1);
            if(outcome.equals("timeout"))setup.thenThrow(new AiFailure("AI_TIMEOUT"));else setup.thenReturn(outcome.equals("invalid")?json.createObjectNode():followup);
            var result=StyledTailAnatomy.review(client,ai,json,seeds().get(0),seeds(),raw("COMPLETE_CONNECTED","COMPLETE_CONNECTED"));
            assertThat(result.path("passed").asBoolean()).as(outcome).isEqualTo(outcome.equals("resolved"));
            assertThat(result.path("reviewCalls").asInt()).isEqualTo(2);verify(client,times(2)).structuredImagesWithReasoning(anyString(),contains("No prior verdict"),anyMap(),anyMap(),eq("medium"));
            var r=report();for(String f:List.of("photoSha256","model"))r.set(f,result.path(f));r.set("tailEvidence",result);
            assertThat(StyledTailAnatomy.boundPass(r)).isEqualTo(outcome.equals("resolved"));
            r.put("photoSha256","0".repeat(64));assertThat(StyledTailAnatomy.boundPass(r)).isFalse();
        }
    }
    @Test void disagreementWithGeneralReviewIsReobservedAndCannotBuyAnUnconfirmedEdit(){
        for(boolean resolves:List.of(true,false)){
            var client=mock(OpenAiResponsesClient.class);var first=raw("NOT_DISCERNIBLE","COMPLETE_CONNECTED");
            var second=raw(resolves?"COMPLETE_CONNECTED":"NOT_DISCERNIBLE","COMPLETE_CONNECTED");((tools.jackson.databind.node.ArrayNode)second.path("views")).remove(1);
            when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenReturn(first,second);
            var report=report();var general=(ObjectNode)report.path("propertyReview").deepCopy();general.put("photoTail","OBSCURED").put("photoEvidence","Photo does not show the tail.");
            var evidence=StyledTailAnatomy.review(client,ai,json,seeds().get(0),seeds(),general);
            assertThat(evidence.path("passed").asBoolean()).isEqualTo(resolves);assertThat(evidence.path("reviewCalls").asInt()).isEqualTo(2);
            StyledTailAnatomy.merge(json,report,evidence);
            if(!resolves){assertThat(report.path("qualityDecision").asText()).isEqualTo("UNCERTAIN");assertThat(report.path("issues").isEmpty()).isTrue();
                assertThat(StyledSeedRepair.plan(client,json,seeds(),report).path("status").asText()).isEqualTo("UNCERTAIN_VERDICT");}
            verify(client,times(2)).structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"));
        }
    }
    @Test void clearDefectsAreNotRerolledAndUnknownViewsAreNeverLearnedAsNegative(){
        var client=mock(OpenAiResponsesClient.class);when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenReturn(raw("NOT_DISCERNIBLE","COMPLETE_CONNECTED"));
        var result=StyledTailAnatomy.review(client,ai,json,seeds().get(0),fixture("case-b"),raw("COMPLETE_CONNECTED","COMPLETE_CONNECTED"));assertThat(result.path("decision").asText()).isEqualTo("CONFIRMED_DEFECT");
        verify(client).structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"));
        var report=report();StyledTailAnatomy.merge(json,report,assess(raw("NOT_DISCERNIBLE","UNCERTAIN"),fixture("case-b")));
        assertThat(StyledSeedRepair.plan(mock(OpenAiResponsesClient.class),json,seeds(),report).path("directions").toString()).isEqualTo("[\"west\"]");
        assertThat(StyledTailAnatomy.unresolved(report)).isTrue();
    }
}
