package org.shelterconnect.api.community;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import com.sun.net.httpserver.HttpServer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import org.shelterconnect.api.web.FeatureException;
import static org.assertj.core.api.Assertions.*;

class CommunityStorageTest {
    HttpServer server;String base;SupabaseCommunityStorage storage;List<String> calls=new ArrayList<>();
    String key=UUID.randomUUID()+"/"+UUID.randomUUID()+"/"+"a".repeat(64)+".png";
    AtomicReference<String> bucket=new AtomicReference<>("{\"id\":\"community-media\",\"public\":false}");
    AtomicReference<String> signature=new AtomicReference<>();
    @BeforeEach void setup()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);base="http://127.0.0.1:"+server.getAddress().getPort()+"/storage/v1";
        signature.set("{\"signedURL\":\"/object/sign/community-media/"+key+"?token=fixture.token\"}");
        server.createContext("/",exchange->{
            calls.add(exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath());
            assertThat(exchange.getRequestHeaders().getFirst("apikey")).isEqualTo("sb_secret_test_fixture");
            String result=exchange.getRequestURI().getPath().contains("/bucket/")?bucket.get():exchange.getRequestURI().getPath().contains("/sign/")?signature.get():"{}";
            if(exchange.getRequestURI().getPath().contains("/sign/"))assertThat(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).contains("\"expiresIn\":60");
            var bytes=result.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();storage=new SupabaseCommunityStorage(base,"community-media","sb_secret_test_fixture",true,new JsonMapper(),HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }
    @AfterEach void close(){server.stop(0);}
    @Test void validatesPrivateBucketAndExactSignedPath(){assertThat(storage.sign(key)).isEqualTo(base+"/object/sign/community-media/"+key+"?token=fixture.token");assertThat(calls).hasSize(2);}
    @Test void refusesPublicBucketBeforeSendingImage(){bucket.set("{\"id\":\"community-media\",\"public\":true}");assertThatThrownBy(()->storage.put(key,new byte[]{1})).isInstanceOf(FeatureException.class);assertThat(calls).hasSize(1);}
    @Test void refusesRemoteRedirectStyleOrDifferentObjectSignatures(){for(String link:List.of("https://elsewhere.invalid/object?token=fixture","/object/sign/community-media/other.png?token=fixture","/object/sign/community-media/"+key+"?token=x&extra=x")){signature.set(new JsonMapper().writeValueAsString(Map.of("signedURL",link)));assertThatThrownBy(()->storage.sign(key)).isInstanceOf(FeatureException.class);}}
    @Test void boundsStorageResponseAndValidatesServerSelectedPaths(){bucket.set("x".repeat(70000));assertThatThrownBy(()->storage.sign(key)).isInstanceOf(FeatureException.class);calls.clear();assertThatThrownBy(()->storage.sign("../secret")).isInstanceOf(FeatureException.class);assertThat(calls).isEmpty();}
    @Test void imageDecoderRejectsUnsupportedOrOversizedInputAndNormalizesPixels()throws Exception{
        byte[] jpeg=image(32,32,"jpeg");byte[] normalized=CommunityImage.normalize(jpeg);assertThat(ImageIO.read(new java.io.ByteArrayInputStream(normalized)).getWidth()).isEqualTo(32);assertThat(normalized[0]).isEqualTo((byte)137);
        for(byte[] invalid:List.of(new byte[0],new byte[CommunityImage.MAX_BYTES+1],"<svg/>".getBytes(),image(8193,16,"png"),image(16,16,"gif")))assertThatThrownBy(()->CommunityImage.normalize(invalid)).isInstanceOf(FeatureException.class);
    }
    private byte[] image(int width,int height,String format)throws Exception{var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),format,output);return output.toByteArray();}
}
