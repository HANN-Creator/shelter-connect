package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Recorded observations replayed through the real production reviewer, not a live vision result. */
public final class CoatIdentityReplay {
    private CoatIdentityReplay(){}
    public static JsonNode archived(JsonMapper json)throws Exception {
        return json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/coat-identity-v33/deployed-review.json")));
    }
    public static List<byte[]> seeds()throws Exception {
        var seeds=new ArrayList<byte[]>();
        for(String d:StyledSpriteCodec.DIRECTIONS)seeds.add(Files.readAllBytes(Path.of("scripts/fixtures/tail-spine-v31",d+".png")));
        return seeds;
    }
    public static ObjectNode syntheticObservation(JsonMapper json,String decision)throws Exception {
        var observation=(ObjectNode)archived(json).at("/coatEvidence/observation").deepCopy();
        ((ObjectNode)observation.at("/views/0")).putObject("nonCoatIdentity").put("decision",decision).put("confidence",.95)
            .put("evidence","Synthetic test observation: the ear form, muzzle and body proportions correspond independently of fur patches.");
        return observation;
    }
    public static JsonNode review(JsonMapper json,byte[] photo,List<byte[]> seeds,JsonNode traits,JsonNode observation)throws Exception {
        var archived=archived(json);var client=mock(OpenAiResponsesClient.class);
        when(client.structuredImages(anyString(),anyString(),anyMap(),anyMap())).thenReturn(archived.at("/coatEvidence/originalPropertyReview"));
        when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),anyString()))
            .thenReturn(observation,archived.at("/tailEvidence/observation"));
        var report=StyledRecoveryReview.seeds(client,new AiProperties(true,"replay-only","recorded-observations",60),json,
            photo,seeds,json.createArrayNode(),List.of(),traits);
        verify(client,times(1)).structuredImages(anyString(),anyString(),anyMap(),anyMap());
        verify(client,times(2)).structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),anyString());
        return report;
    }
}
