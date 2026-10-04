package org.shelterconnect.api.asset;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssetManifestServiceTest {
    final JsonMapper json=new JsonMapper();
    @Test void legacyAndPartialOrMismatchedVariantsFallBackWithoutSigningPartialAssets() {
        for(int count:List.of(0,1,2,3)) {
            var steps=steps(count);if(count==3) ((tools.jackson.databind.node.ObjectNode)steps.get(2).result().at("/mapPixel/validation")).put("paletteSha256","b".repeat(64));
            var storage=mock(AssetStorage.class);
            var response=published(steps,storage);
            assertThat(response.path("variants").isEmpty()).isTrue();
            assertThat(response.at("/frameSize/width").asInt()).isEqualTo(64);
            verify(storage).sign(List.of("job/BASE.png","job/IDLE.png","job/WALK.png"));
        }
    }
    @Test void completeMapVariantKeepsTimelineButHalvesTextureCoordinatesAndAnchor() {
        var response=published(steps(3),mock(AssetStorage.class));var map=response.at("/variants/MAP_32");
        assertThat(map.path("generatorVersion").asText()).isEqualTo("map-pixel-v2");
        assertThat(map.at("/frameSize/width").asInt()).isEqualTo(32);
        assertThat(map.at("/anchorPixels/x").asInt()).isEqualTo(16);
        assertThat(map.at("/anchorPixels/y").asInt()).isEqualTo(30);
        assertThat(map.at("/animations/WALK/frameCount").asInt()).isEqualTo(48);
        assertThat(map.path("availableActions")).isEqualTo(response.path("availableActions"));
        var old=response.at("/animations/WALK/frames");var frames=map.at("/animations/WALK/frames");
        for(int i=0;i<48;i++) {
            assertThat(frames.get(i).path("x").asInt()).isEqualTo(old.get(i).path("x").asInt()/2);
            assertThat(frames.get(i).path("durationMs")).isEqualTo(old.get(i).path("durationMs"));
        }
        assertThat(map.toString()).doesNotContain("paletteSha256","validation","renderedSource");
    }
    @Test void existingV1MapsRemainReadableWithTheirActualVersion() {
        var steps=steps(3);
        for(var step:steps) ((tools.jackson.databind.node.ObjectNode)step.result().at("/mapPixel/validation")).put("converterVersion","map-pixel-v1");
        var map=published(steps,mock(AssetStorage.class)).at("/variants/MAP_32");
        assertThat(map.path("generatorVersion").asText()).isEqualTo("map-pixel-v1");
        assertThat(map.at("/animations/WALK/frameCount").asInt()).isEqualTo(48);
    }
    @Test void rollingDeployMixturesAndUnknownVersionsFallBackWithoutSigningMapAssets() {
        for(String version:List.of("map-pixel-v1","map-pixel-v99","")) {
            var steps=steps(3);
            ((tools.jackson.databind.node.ObjectNode)steps.get(1).result().at("/mapPixel/validation")).put("converterVersion",version);
            var storage=mock(AssetStorage.class);
            assertThat(published(steps,storage).path("variants").isEmpty()).isTrue();
            verify(storage).sign(List.of("job/BASE.png","job/IDLE.png","job/WALK.png"));
        }
    }
    private tools.jackson.databind.JsonNode published(List<AssetStore.Step> steps,AssetStorage storage) {
        var store=mock(AssetStore.class);var dog=UUID.randomUUID();
        var job=new AssetStore.Job(UUID.randomUUID(),dog,"APPROVED",null,Instant.now(),steps,
            List.of("BASE","IDLE","WALK"),1,1,null,true);
        when(store.available(dog)).thenReturn(job);
        when(storage.sign(anyList())).thenAnswer(c->{var links=new LinkedHashMap<String,String>();for(String k:c.<List<String>>getArgument(0))links.put(k,"https://assets.invalid/"+k);return links;});
        return json.valueToTree(new AssetManifestService(store,storage).published(dog));
    }
    private List<AssetStore.Step> steps(int maps) {
        var result=new ArrayList<AssetStore.Step>();
        for(String action:List.of("BASE","IDLE","WALK")) {
            int count=action.equals("BASE")?1:action.equals("IDLE")?16:48;
            var meta=new LinkedHashMap<String,Object>(Map.of("key","job/"+action+".png","frameCount",count,
                "width",64,"height",64,"durationMs",30,"loop",true,"holdLastFrame",false,"returnToIdle","DIRECT"));
            if(result.size()<maps) meta.put("mapPixel",Map.of("key","job/map-32/"+action+".png","frameCount",count,"width",32,"height",32,
                "validation",Map.of("converterVersion","map-pixel-v2","paletteSha256","a".repeat(64),"paletteChecked",true,"boundsChecked",true,"transparencyChecked",true)));
            result.add(new AssetStore.Step(action,"SUCCEEDED",json.valueToTree(meta)));
        }
        return result;
    }
}
