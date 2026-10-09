package org.shelterconnect.api.asset;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
class StyledPixelLabClientTest {
    HttpServer server;StyledPixelLabClient client;JsonMapper json=JsonMapper.builder().build();byte[] png;
    String body="{}",requestPath;int status=200;byte[] archive;
    @BeforeEach void setup() throws Exception {
        png=new StyledSpriteCodecTest().image(32);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",ex->{requestPath=ex.getRequestURI().getPath();assertThat(ex.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-secret");ex.getRequestBody().readAllBytes();byte[] bytes=requestPath.endsWith("/zip")?archive:body.getBytes(java.nio.charset.StandardCharsets.UTF_8);ex.sendResponseHeaders(status,bytes.length);ex.getResponseBody().write(bytes);ex.close();});server.start();
        client=new StyledPixelLabClient(PixelLabClientTest.properties(),json,AssetHttp.client(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"));
    }
    @AfterEach void stop() {server.stop(0);}
    @Test void correctEndpointsAndUncertainAcknowledgementArePreserved() {
        UUID id=UUID.randomUUID();body=json.writeValueAsString(Map.of("background_job_id",id));
        assertThat(client.submit(true,json.readTree("{}"))).isEqualTo(id);assertThat(requestPath).isEqualTo("/create-character-pro");
        assertThat(client.submit(false,json.readTree("{}"))).isEqualTo(id);assertThat(requestPath).isEqualTo("/animate-pixminimax");
        assertThat(client.editAnimation(json.readTree("{}"))).isEqualTo(id);assertThat(requestPath).isEqualTo("/edit-animation-v2");
        assertThat(client.editSeedEyes(json.readTree("{}"))).isEqualTo(id);assertThat(requestPath).isEqualTo("/inpaint-v3");
        assertThat(client.editSeeds(json.readTree("{}"))).isEqualTo(id);assertThat(requestPath).isEqualTo("/edit-images-v2");
        body="{}";assertThatThrownBy(()->client.submit(true,json.readTree("{}"))).isInstanceOfSatisfying(AssetProvider.Failure.class,e->assertThat(e.uncertain).isTrue());
        status=429;assertThatThrownBy(()->client.submit(true,json.readTree("{}"))).isInstanceOfSatisfying(AssetProvider.Failure.class,e->assertThat(e.uncertain).isFalse());
    }
    @Test void archiveSelectsFourExactNativeRotationsWithoutExtractingOtherFiles() throws Exception {
        UUID character=UUID.randomUUID();body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("character_id",character)));
        var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)) {
            for(String d:StyledSpriteCodec.DIRECTIONS) {zip.putNextEntry(new ZipEntry("pet/rotations/"+d+".png"));zip.write(png);zip.closeEntry();}
            zip.putNextEntry(new ZipEntry("../../other.txt"));zip.write(new byte[50]);zip.closeEntry();
        }
        archive=out.toByteArray();var result=client.poll(UUID.randomUUID(),true);
        assertThat(result.path("directions").size()).isEqualTo(4);
        assertThat(StyledPixelLabClient.decode(result.at("/directions/east").asText())).isEqualTo(png);
        assertThat(requestPath).isEqualTo("/characters/"+character+"/zip");
    }
    @Test void nineFramesAreRequiredAndProviderUrlsAreNeverFollowed() {
        body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("images",Collections.nCopies(9,Map.of("base64",Base64.getEncoder().encodeToString(png))))));
        assertThat(client.poll(UUID.randomUUID(),false).path("frames").size()).isEqualTo(9);
        body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("images",List.of(Map.of("url","http://127.0.0.1/internal")))));
        assertThatThrownBy(()->client.poll(UUID.randomUUID(),false)).isInstanceOf(AssetException.class);
    }
    @Test void baseEditsKeepFourViewOrderAndMotionAcceptsExactlyNineFortyPixelFrames()throws Exception {
        var images=new ArrayList<Object>();for(int i=0;i<4;i++){var image=StyledSpriteCodec.nativeFrame(png);image.setRGB(10,10,0xffaa0000+i);
            images.add(Map.of("base64",Base64.getEncoder().encodeToString(StyledSpriteCodec.png(image))));}
        body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("images",images)));
        var result=client.pollSeeds(UUID.randomUUID());for(int i=0;i<4;i++)assertThat(StyledSpriteCodec.nativeFrame(StyledPixelLabClient.decode(result.at("/directions/"+StyledSpriteCodec.DIRECTIONS.get(i)).asText())).getRGB(10,10)).isEqualTo(0xffaa0000+i);
        byte[] padded=StyledSpriteCodec.paddedSeed(png);
        body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("images",Collections.nCopies(9,Map.of("base64",Base64.getEncoder().encodeToString(padded))))));
        assertThat(client.poll(UUID.randomUUID(),false).path("frames").size()).isEqualTo(9);
        assertThatThrownBy(()->client.pollSeeds(UUID.randomUUID())).isInstanceOf(AssetException.class);
    }
    @Test void selectedViewsUseExactRequestedOrderAndRejectExtraMissingOrAmbiguousFrames(){
        String b=Base64.getEncoder().encodeToString(png);body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("images",List.of(Map.of("base64",b)))));
        assertThat(client.pollSeeds(UUID.randomUUID(),List.of("north")).path("directions").propertyNames()).containsExactly("north");
        assertThatThrownBy(()->client.pollSeeds(UUID.randomUUID(),List.of("north","east"))).hasMessage("STYLED_FRAME_COUNT_INVALID");
        for(var dirs:List.of(List.<String>of(),List.of("north","north"),List.of("east","south"),List.of("unknown")))assertThatThrownBy(()->client.pollSeeds(UUID.randomUUID(),dirs)).hasMessage("SEED_REPAIR_SELECTION_INVALID");
    }
    @Test void eyeEditRequiresAnExactNativeFourViewStripAndNeverFollowsUrls() {
        byte[] raw=StyledSeedEyeRepair.png(StyledSeedEyeRepair.strip(Collections.nCopies(4,png)));
        body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("image",StyledSeedEyeRepair.encoded(raw))));
        assertThat(Base64.getDecoder().decode(client.pollSeedEyes(UUID.randomUUID()).path("eyeSheet").asText())).isEqualTo(raw);
        body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("image",StyledSeedEyeRepair.encoded(png))));
        assertThatThrownBy(()->client.pollSeedEyes(UUID.randomUUID())).isInstanceOf(AssetException.class);
        body=json.writeValueAsString(Map.of("status","completed","last_response",Map.of("image",Map.of("url","http://127.0.0.1/internal"))));
        assertThatThrownBy(()->client.pollSeedEyes(UUID.randomUUID())).isInstanceOf(AssetException.class);
        assertThat(requestPath).startsWith("/background-jobs/");
    }
}
