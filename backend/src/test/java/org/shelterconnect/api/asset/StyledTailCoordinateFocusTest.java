package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledTailCoordinateFocusTest {
    static final Path FIXTURE=Path.of("scripts/fixtures/tail-coordinate-focus-v20");
    final JsonMapper json=JsonMapper.builder().build();
    final AiProperties ai=new AiProperties(true,"fixture-key","gpt-5.6-luna",30);
    static List<byte[]> seeds()throws Exception{var result=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)result.add(Files.readAllBytes(FIXTURE.resolve(d+".png")));return result;}
    JsonNode old()throws Exception{return json.readTree(Files.readAllBytes(FIXTURE.resolve("deployed-review.json")));}
    JsonNode raw(String west,String east){return StyledSeedTailEvidenceTest.observation(json,"OBSCURED",west,east,.95);}
    @Test void deployedTransparentCoordinatesFocusOnlyEastWithoutChangingSource()throws Exception{
        var old=old();var seeds=seeds();var before=seeds.stream().map(StyledSpriteCodec::sha).toList();
        assertThat(StyledSeedQualityAgent.binding(seeds)).isEqualTo(old.path("inputSha256").asText());
        var e=StyledSeedTailEvidence.grounded(json,old.at("/tailEvidence/observation"),seeds);
        assertThat(e.path("uncertainDirections").toString()).isEqualTo("[\"east\"]");
        assertThat(e.at("/pixelAudit/west/completeContour").asBoolean()).isTrue();
        var focus=StyledTailCoordinateFocus.prepare(json,seeds.get(0),seeds,"east",e);
        assertThat(focus.images()).hasSize(3);assertThat(focus.metadata().path("cropBoundsOriginalXYExclusive").toString()).isEqualTo("[0,6,16,21]");
        assertThat(focus.metadata().path("transparentCoordinates")).isEqualTo(json.readTree("[{\"x\":2,\"y\":11},{\"x\":2,\"y\":10},{\"x\":2,\"y\":9}]"));
        assertThat(focus.task()).contains("ONLY east","absolute native x,y","alphaRows","not anatomy","truncate a path");
        assertThat(seeds.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(before);
        // Source-colour centers in the crop are exact integer enlarged pixels; label overlays are separate.
        var grid=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(focus.images().values().stream().toList().get(2)));
        var source=StyledSpriteCodec.nativeFrame(seeds.get(3));
        for(int y=6;y<21;y++)for(int x=0;x<16;x++)if((source.getRGB(x,y)>>>24)!=0)
            assertThat(grid.getRGB(20+x*36+18,30+(y-6)*36+20)).isEqualTo(source.getRGB(x,y));
    }
    @Test void boundedCallsPreservePhotoAndOtherDirectionAndRecordEachResponse(){
        var first=raw("UNCERTAIN","UNCERTAIN");var good=raw("COMPLETE_CONNECTED","COMPLETE_CONNECTED");
        var client=mock(OpenAiResponsesClient.class);
        when(client.structuredImages(anyString(),anyString(),anyMap(),anyMap())).thenReturn(first,good.at("/views/0"),good.at("/views/1"));
        byte[] seed=StyledSeedTailEvidenceTest.tailSeed();var result=StyledSeedTailEvidence.review(client,ai,json,seed,Collections.nCopies(4,seed));
        assertThat(result.path("passed").asBoolean()).isTrue();assertThat(result.path("observationHistory").size()).isEqualTo(3);
        assertThat(result.at("/observationHistory/1/observation/views/1")).isEqualTo(first.at("/views/1"));
        for(String field:List.of("photoTail","photoConfidence","photoEvidence"))assertThat(result.path("observation").path(field)).isEqualTo(first.path(field));
        assertThat(result.path("refinements").size()).isEqualTo(2);
        assertThat(result.at("/refinements/0/response")).isEqualTo(good.at("/views/0"));
        assertThat(result.at("/refinements/1/imageHashes").size()).isEqualTo(3);
        verify(client,times(3)).structuredImages(anyString(),anyString(),anyMap(),anyMap());
        verify(client).structuredImages(anyString(),contains("ONLY west"),anyMap(),eq(StyledSeedTailEvidence.viewSchema(List.of("west"))));
        verify(client).structuredImages(anyString(),contains("ONLY east"),anyMap(),eq(StyledSeedTailEvidence.viewSchema(List.of("east"))));
    }
    @Test void realFocusedObservationReplaysWithExactFourPngsButNeverOverridesGeneralDefects()throws Exception{
        var old=old();var saved=json.readTree(Files.readAllBytes(FIXTURE.resolve("live-focus-review.json")));var seeds=seeds();
        assertThat(saved.path("inputSha256").asText()).isEqualTo(StyledSeedQualityAgent.binding(seeds));
        assertThat(saved.path("photoSha256")).isEqualTo(old.path("photoSha256"));
        var grounded=StyledSeedTailEvidence.grounded(json,saved.path("observation"),seeds);
        assertThat(grounded.path("passed").asBoolean()).isTrue();assertThat(grounded.at("/pixelAudit/east/completeContour").asBoolean()).isTrue();
        assertThat(saved.at("/observation/views/0")).isEqualTo(old.at("/tailEvidence/observation/views/0"));
        var independentlyFailed=old.deepCopy();StyledSeedTailEvidence.merge(json,(ObjectNode)independentlyFailed,saved);
        assertThat(independentlyFailed.path("passed").asBoolean()).isFalse();
        assertThat(independentlyFailed.path("issues")).isEqualTo(old.path("issues"));
        // Valid localization alone cannot authorize a BASE or motion pack.
        assertThat(StyledSeedTailEvidence.boundPass(json.createObjectNode().set("tailEvidence",saved))).isFalse();
    }
    @Test void emptyPathsUseFullGridAndFailuresNeverAuthorizeApprovalOrAnotherLoop(){
        for(String mode:List.of("timeout","wrong-direction","extra-fields","still-uncertain")){
            var first=raw("UNCERTAIN","COMPLETE_CONNECTED");((ObjectNode)first.at("/views/0")).putArray("tailPixelPath");
            byte[] seed=StyledSeedTailEvidenceTest.tailSeed();var seeds=Collections.nCopies(4,seed);
            var e=StyledSeedTailEvidence.grounded(json,first,seeds);
            assertThat(StyledTailCoordinateFocus.prepare(json,seed,seeds,"west",e).metadata().path("cropBoundsOriginalXYExclusive").toString()).isEqualTo("[0,0,32,32]");
            var client=mock(OpenAiResponsesClient.class);var call=when(client.structuredImages(anyString(),anyString(),anyMap(),anyMap())).thenReturn(first);
            if(mode.equals("timeout"))call.thenThrow(new AiFailure("AI_TIMEOUT"));
            else{var reply=(ObjectNode)first.at("/views/0").deepCopy();if(mode.equals("wrong-direction"))reply.put("direction","east");
                if(mode.equals("extra-fields"))reply.put("photoTail","VISIBLE_NATURALLY_SHORT");call.thenReturn(reply);}
            var result=StyledSeedTailEvidence.review(client,ai,json,seed,seeds);assertThat(result.path("passed").asBoolean()).as(mode).isFalse();
            assertThat(result.at("/observation/views/1")).isEqualTo(first.at("/views/1"));
            assertThat(result.path("refinements").size()).isEqualTo(1);verify(client,times(2)).structuredImages(anyString(),anyString(),anyMap(),anyMap());
        }
    }
    @Test void geometryOrHighConfidenceCannotTurnTransparentOrMissingTailIntoPass(){
        byte[] seed=StyledSeedTailEvidenceTest.tailSeed();var first=raw("UNCERTAIN","COMPLETE_CONNECTED");
        for(String category:List.of("COMPLETE_CONNECTED","NOT_DISCERNIBLE","SHORT_STUB")){
            var v=(ObjectNode)raw(category,"COMPLETE_CONNECTED").at("/views/0").deepCopy();v.put("confidence",.99);
            v.set("tailPixelPath",json.valueToTree(List.of(Map.of("x",0,"y",0),Map.of("x",1,"y",0),Map.of("x",2,"y",0),Map.of("x",3,"y",0))));
            var client=mock(OpenAiResponsesClient.class);when(client.structuredImages(anyString(),anyString(),anyMap(),anyMap())).thenReturn(first,v);
            assertThat(StyledSeedTailEvidence.review(client,ai,json,seed,Collections.nCopies(4,seed)).path("passed").asBoolean()).as(category).isFalse();
        }
    }
    @Test void oneSideTimeoutDoesNotReplaceItsEvidenceOrDiscardTheOtherSideResult(){
        var first=raw("UNCERTAIN","UNCERTAIN");var good=raw("COMPLETE_CONNECTED","COMPLETE_CONNECTED");
        var client=mock(OpenAiResponsesClient.class);
        when(client.structuredImages(anyString(),anyString(),anyMap(),anyMap())).thenReturn(first).thenThrow(new AiFailure("AI_TIMEOUT")).thenReturn(good.at("/views/1"));
        byte[] seed=StyledSeedTailEvidenceTest.tailSeed();var result=StyledSeedTailEvidence.review(client,ai,json,seed,Collections.nCopies(4,seed));
        assertThat(result.path("passed").asBoolean()).isFalse();assertThat(result.path("uncertainDirections").toString()).isEqualTo("[\"west\"]");
        assertThat(result.at("/observation/views/0")).isEqualTo(first.at("/views/0"));
        assertThat(result.at("/observation/views/1")).isEqualTo(good.at("/views/1"));
        assertThat(result.at("/refinements/0/failureCode").asText()).isEqualTo("QUALITY_AI_TIMEOUT");
        assertThat(result.at("/refinements/1/response")).isEqualTo(good.at("/views/1"));
        assertThat(result.path("refinementFailureCode").asText()).isEqualTo("QUALITY_AI_TIMEOUT");
        verify(client,times(3)).structuredImages(anyString(),anyString(),anyMap(),anyMap());
    }
}
