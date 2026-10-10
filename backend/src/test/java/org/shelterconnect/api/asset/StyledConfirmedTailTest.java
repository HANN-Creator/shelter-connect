package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledConfirmedTailTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Path root=Path.of("scripts/fixtures/confirmed-tail-v33");
    JsonNode actual()throws Exception{return json.readTree(Files.readAllBytes(root.resolve("review.json")));}

    @Test void actualNineFramesAndIndependentObservationsPermitRepairButNeverApproval()throws Exception {
        var original=actual();var evidence=json.readTree(Files.readAllBytes(root.resolve("evidence.json")));
        byte[] sheet=Files.readAllBytes(root.resolve("idle-south.png"));
        assertThat(StyledSpriteCodec.sha(sheet)).isEqualTo(evidence.path("sheetSha256").asText());
        var seeds=new ArrayList<byte[]>();
        for(String d:StyledSpriteCodec.DIRECTIONS) {
            byte[] seed=Files.readAllBytes(root.resolve(d+".png"));
            assertThat(StyledSpriteCodec.sha(seed)).isEqualTo(evidence.at("/seedHashes/"+d).asText());
            seeds.add(StyledSpriteCodec.paddedSeed(seed));
        }
        var frames=StyledSpriteCodec.frames(sheet);assertThat(frames).hasSize(9);
        assertThat(frames.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(original.path("frameHashes").valueStream().map(JsonNode::asText).toList());
        var client=mock(OpenAiResponsesClient.class);
        when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium")))
            .thenReturn(original.path("initialVision"),original.path("consistencyReview"));
        var replay=StyledMotionReview.review(client,new AiProperties(true,"fixture-key","gpt-5.6-luna",60),json,
            json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),seeds,frames,"IDLE","south",json.createArrayNode());
        assertThat(replay.path("motionDecision").asText()).isEqualTo("UNCERTAIN");
        assertThat(replay.path("passed").asBoolean()).isFalse();
        assertThat(replay.path("uncertainProperties").toString()).isEqualTo("[\"identity\"]");
        assertThat(replay.path("initialVision")).isEqualTo(original.path("initialVision"));
        assertThat(replay.path("consistencyReview")).isEqualTo(original.path("consistencyReview"));
        var before=replay.deepCopy();
        assertThat(StyledMotionReview.confirmedTailRepair(replay)).isTrue();
        assertThat(StyledMotionReview.unresolved(replay)).isTrue();
        assertThat(StyledMotionReview.boundPass(replay)).isFalse();assertThat(replay).isEqualTo(before);
        var payload=StyledRecovery.motionPayload(json,frames,"IDLE","south",replay,1);
        assertThat(payload.path("frames").size()).isEqualTo(9);
        assertThat(payload.path("description").asText()).contains("Both observations confirm a tail defect", "preserving all other anatomy").hasSizeLessThanOrEqualTo(2000);
        verify(client,times(2)).structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"));
    }

    @ParameterizedTest
    @ValueSource(strings={"empty-identity-frames","unrelated-identity-frame","invalid-frame","text-frame","both-uncertain","tail-uncertain","tail-pass","no-shared-tail-frames","other-uncertainty","missing-second","passed"})
    void unsupportedEvidenceCannotAuthorizePaidRepair(String mutation)throws Exception {
        var r=(ObjectNode)actual();
        var identity=(ObjectNode)StyledMotionReview.property(r.path("initialVision"),"identity");
        var tail=(ObjectNode)StyledMotionReview.property(r.path("consistencyReview"),"tail");
        switch(mutation) {
            case "empty-identity-frames" -> identity.putArray("frames");
            case "unrelated-identity-frame" -> identity.putArray("frames").add(0);
            case "invalid-frame" -> identity.putArray("frames").add(9);
            case "text-frame" -> identity.putArray("frames").add("1");
            case "both-uncertain" -> ((ObjectNode)StyledMotionReview.property(r.path("consistencyReview"),"identity")).put("state","UNCERTAIN");
            case "tail-uncertain" -> tail.put("state","UNCERTAIN");
            case "tail-pass" -> tail.put("state","PASS");
            case "no-shared-tail-frames" -> tail.putArray("frames").add(8);
            case "other-uncertainty" -> r.withArray("uncertainProperties").add("eyes");
            case "missing-second" -> r.remove("consistencyReview");
            case "passed" -> r.put("passed",true);
        }
        assertThat(StyledMotionReview.confirmedTailRepair(r)).isFalse();
    }
}
