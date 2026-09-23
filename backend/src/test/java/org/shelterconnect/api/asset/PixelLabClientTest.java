package org.shelterconnect.api.asset;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PixelLabClientTest {
    HttpServer server; PixelLabClient client; JsonMapper json=JsonMapper.builder().build();
    int status=202; String response;AtomicReference<JsonNode> submitted=new AtomicReference<>();
    static AssetProperties properties() { return new AssetProperties(true,true,"test-secret","sb_secret_testing","dog-photos","dog-assets",10); }
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-secret");
            byte[] body=exchange.getRequestBody().readAllBytes();if(body.length>0)submitted.set(json.readTree(body));
            byte[] out=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,out.length);exchange.getResponseBody().write(out);exchange.close();
        });server.start();
        client=new PixelLabClient(properties(),json,AssetHttp.client(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"));
        response="{\"background_job_id\":\""+UUID.randomUUID()+"\"}";
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void correctPhotoAndEightMotionRequestsUseNativeSprites() {
        client.submitBase(new PhotoAppearance.Input(SpriteNormalizerTest.frame(0,0),SpriteNormalizerTest.frame(1,0),"white fur and pink nose patch"));
        assertThat(submitted.get().at("/reference_images/0/size/width").asInt()).isEqualTo(64);
        for(var action:AssetAction.values())if(action!=AssetAction.BASE) {
            byte[] approvedBase=SpriteNormalizerTest.frame(0,0);
            client.submit(action,approvedBase);
            assertThat(Base64.getDecoder().decode(submitted.get().at("/reference_image/base64").asText())).isEqualTo(approvedBase);
            assertThat(submitted.get().has("style_image")).isFalse();
            assertThat(submitted.get().has("reference_images")).isFalse();
            assertThat(submitted.get().at("/image_size/width").asInt()).isEqualTo(64);
            assertThat(submitted.get().path("action").asText().length()).isLessThanOrEqualTo(500);
            assertThat(submitted.get().path("action").asText()).contains("Same puppy identity");
            assertThat(submitted.get().path("no_background").asBoolean()).isTrue();
        }
    }
    @Test void baseSeparatesSubjectIdentityFromTheApprovedArtStyle() throws Exception {
        byte[] photo=SpriteNormalizerTest.frame(0,0);
        byte[] head=SpriteNormalizerTest.frame(1,0);
        client.submitBase(new PhotoAppearance.Input(photo,head,"coat: white ivory. nose: black with a pink patch"));
        JsonNode request=submitted.get();
        assertThat(request.path("reference_images").size()).isEqualTo(2);
        assertThat(Base64.getDecoder().decode(request.at("/reference_images/0/image/base64").asText())).isEqualTo(photo);
        assertThat(Base64.getDecoder().decode(request.at("/reference_images/1/image/base64").asText())).isEqualTo(head);
        assertThat(request.path("description").asText()).contains("white ivory", "pink patch");
        assertThat(request.path("description").asText().length()).isLessThanOrEqualTo(2000);
        byte[] style=Base64.getDecoder().decode(request.at("/style_image/image/base64").asText());
        try(var expected=getClass().getResourceAsStream("/sprite-style/cozy-dog-v1.png")) {
            assertThat(expected).isNotNull();
            assertThat(style).isEqualTo(expected.readAllBytes()).isNotEqualTo(photo);
        }
        assertThat(SpriteNormalizer.dimensions(style,64)).containsExactly(64,64);
        assertThat(request.at("/style_image/size/width").asInt()).isEqualTo(64);
        assertThat(request.at("/style_image/size/height").asInt()).isEqualTo(64);
        assertThat(request.at("/style_image/usage_description").asText()).contains("style only", "Do not copy");
        assertThat(request.at("/style_options/color_palette").asBoolean(true)).isFalse();
        for(String key:List.of("outline","detail","shading"))
            assertThat(request.at("/style_options/"+key).asBoolean()).isTrue();
        assertThat(request.path("description").asText()).contains("modestly enlarged head", "PHOTO reference", "STYLE reference");
    }
    @Test void ambiguousSubmissionIsNotMisreportedAsSafeToRetry() {
        status=503;
        assertThatThrownBy(()->client.submit(AssetAction.WALK,SpriteNormalizerTest.frame(0,0))).isInstanceOfSatisfying(AssetProvider.Failure.class,e->assertThat(e.uncertain).isTrue());
        status=202;response="{}";
        assertThatThrownBy(()->client.submit(AssetAction.WALK,SpriteNormalizerTest.frame(0,0))).isInstanceOfSatisfying(AssetProvider.Failure.class,e->assertThat(e.uncertain).isTrue());
        status=402;
        assertThatThrownBy(()->client.submit(AssetAction.WALK,SpriteNormalizerTest.frame(0,0))).isInstanceOfSatisfying(AssetProvider.Failure.class,e->assertThat(e.uncertain).isFalse());
    }
    @Test void baseCannotBypassAppearancePreparationAndLongestDescriptionsFitProviderLimit() {
        assertThatThrownBy(()->client.submit(AssetAction.BASE,SpriteNormalizerTest.frame(0,0))).isInstanceOf(AssetException.class);
        assertThat(submitted.get()).isNull();
        StringJoiner description=new StringJoiner(". ");
        for(String feature:PhotoAppearance.FEATURES)description.add(feature+": "+"x".repeat(120));
        byte[] photo=SpriteNormalizerTest.frame(0,0);
        client.submitBase(new PhotoAppearance.Input(photo,photo,description.toString()));
        assertThat(submitted.get().path("description").asText().length()).isLessThanOrEqualTo(2000);
    }
    @Test void pollPreservesTheReturnedSeedPlusFrames() {
        status=200;String frame=Base64.getEncoder().encodeToString(SpriteNormalizerTest.frame(0,0));
        response=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("images",Collections.nCopies(16,Map.of("base64",frame)))));
        assertThat(client.poll(UUID.randomUUID()).images()).hasSize(16);
        response="{\"status\":\"failed\"}";assertThat(client.poll(UUID.randomUUID()).status()).isEqualTo("FAILED");
    }
}
