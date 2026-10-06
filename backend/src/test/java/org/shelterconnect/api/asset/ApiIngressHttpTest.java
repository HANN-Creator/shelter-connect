package org.shelterconnect.api.asset;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.shelterconnect.api.auth.*;
import org.shelterconnect.api.web.ApiInputFilter;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.traffic.user-minute-limit=3","app.traffic.write-minute-limit=2"})
@ActiveProfiles("test") @Import(JwtTestConfiguration.class)
class ApiIngressHttpTest {
    @LocalServerPort int port;
    @Autowired JwtTestSupport tokens;
    @MockitoBean AccountRepository accounts;
    @MockitoBean PhotoUploadStore uploads;
    @MockitoBean AssetStorage storage;
    @MockitoBean ShelterAccessService access;
    @MockitoBean StyledAssetStore styled;
    @MockitoBean StyledAssetProvider provider;
    @Autowired JsonMapper json;
    private final HttpClient client=HttpClient.newHttpClient();
    private final UUID dog=UUID.randomUUID();
    @Test void realChunkedJsonCannotPassLimitAndUnauthorizedBodyDoesNotReachDatabase() throws Exception {
        byte[] body=new byte[ApiInputFilter.BODY_BYTES+1];
        var response=send("/v1/me",tokens.token(UUID.randomUUID()),"application/json",body,true);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("REQUEST_TOO_LARGE","requestId");
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
        assertThat(send("/v1/me",null,"application/json",body,true).statusCode()).isEqualTo(401);
        verifyNoInteractions(accounts,storage,uploads);
    }
    @Test void realMultipartRejectsLargeFileLargeMetadataAndTooManyPartsBeforeStorage() throws Exception {
        for(boolean chunked:new boolean[]{false,true}) {
            var response=send(photoPath(),tokens.token(UUID.randomUUID()),"multipart/form-data; boundary=boundary",multipart(new byte[ApiInputFilter.PHOTO_BYTES+1],"{}"),chunked);
            assertThat(response.statusCode()).isEqualTo(413);
            var metadata=send(photoPath(),tokens.token(UUID.randomUUID()),"multipart/form-data; boundary=boundary",multipart(new byte[1]," ".repeat(ApiInputFilter.BODY_BYTES+1)),chunked);
            assertThat(metadata.statusCode()).isEqualTo(413);
        }
        String part="--boundary\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\na\r\n";
        var response=send(photoPath(),tokens.token(UUID.randomUUID()),"multipart/form-data; boundary=boundary",(part.repeat(5)+"--boundary--\r\n").getBytes(StandardCharsets.UTF_8),true);
        assertThat(response.statusCode()).isEqualTo(413);
        verifyNoInteractions(accounts,storage,uploads,access);
    }
    @Test void ordinaryMultipartStillParsesAndUploadsANormalizedImage() throws Exception {
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",out);
        var reservation=new PhotoUploadStore.Reservation(UUID.randomUUID(),dog,UUID.randomUUID(),UUID.randomUUID(),"test-key",UUID.randomUUID(),false);
        when(uploads.begin(any(),eq(dog),any(),anyString())).thenReturn(reservation);
        when(uploads.complete(any(),eq(reservation))).thenReturn(Map.of("photoId",reservation.photoId()));
        var response=send(photoPath(),tokens.token(UUID.randomUUID()),"multipart/form-data; boundary=boundary",multipart(out.toByteArray(),"{}"),true);
        assertThat(response.statusCode()).isEqualTo(200);
        verify(storage).putPhoto(eq(dog),eq("test-key"),any());
    }
    @Test void fivePartSeedReferenceReachesControllerWithOriginalBytesForBothTransferModes() throws Exception {
        var image=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        for(int y=4;y<30;y++)for(int x=8;x<25;x++)image.setRGB(x,y,0xffa07845);
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);byte[] png=out.toByteArray();
        UUID photo=UUID.randomUUID(),job=UUID.randomUUID();String sha=StyledSpriteCodec.sha(png);
        byte[] metadata=json.writeValueAsBytes(Map.of("photoId",photo,"sourcePhotoSha256",sha,
            "expectedSeedHashes",Map.of("south",sha,"north",sha,"west",sha,"east",sha),
            "assessment","POSITIVE","issues",List.of(),"note","Previously approved original native reference for ingress verification."));
        when(styled.referencePhoto(any(),eq(dog),eq(photo))).thenReturn(new StyledAssetStore.ReferencePhoto("dog-photos","source.png"));
        when(storage.photo(dog,"dog-photos","source.png")).thenReturn(png);
        when(styled.referenceExisting(any(),eq(dog),eq(photo),anyString(),any())).thenReturn(Optional.empty());
        when(styled.referenceInsert(any(),eq(dog),eq(photo),any(),anyString(),any(),any())).thenReturn(
            new StyledAssetStore.Job(job,dog,"RUNNING",null,"test",List.of(),null,List.of("BASE"),json.createObjectNode(),json.createObjectNode().put("referenceOnly",true)));
        byte[] body=seedMultipart(metadata,png,List.of("south","north","west","east"));
        for(boolean chunked:new boolean[]{false,true}) {
            var response=send(seedPath(),tokens.token(UUID.randomUUID()),"multipart/form-data; boundary=boundary",body,chunked);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            assertThat(json.readTree(response.body()).at("/data/id").asText()).isEqualTo(job.toString());
            assertThat(send(seedPath(),null,"multipart/form-data; boundary=boundary",body,chunked).statusCode()).isEqualTo(401);
        }
        verify(styled,times(2)).referenceInsert(any(),eq(dog),eq(photo),any(),anyString(),any(),any());
        var stored=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(storage,times(8)).put(anyString(),stored.capture());
        assertThat(stored.getAllValues()).allSatisfy(bytes->assertThat(bytes).isEqualTo(png));
        verifyNoInteractions(provider,accounts,uploads);
    }
    @Test void seedReferenceRejectsExtraDuplicateUnknownAndOversizedPartsBeforeSideEffects() throws Exception {
        for(boolean chunked:new boolean[]{false,true}) {
            for(var names:List.of(List.of("south","north","west","east","extra"),List.of("south","north","west","west"),List.of("south","north","west","file"),List.of("south","north","west"))) {
                var response=send(seedPath(),tokens.token(UUID.randomUUID()),"multipart/form-data; boundary=boundary",seedMultipart("{}".getBytes(StandardCharsets.UTF_8),new byte[1],names),chunked);
                assertThat(response.statusCode()).isEqualTo(names.size()==5?413:400);
            }
            for(boolean largeMetadata:new boolean[]{false,true}) {
                var body=seedMultipart(new byte[largeMetadata?ApiInputFilter.BODY_BYTES+1:2],new byte[largeMetadata?1:ApiInputFilter.BODY_BYTES+1],List.of("south","north","west","east"));
                assertThat(send(seedPath(),tokens.token(UUID.randomUUID()),"multipart/form-data; boundary=boundary",body,chunked).statusCode()).isEqualTo(413);
            }
        }
        verifyNoInteractions(styled,storage,provider,accounts,uploads,access);
    }
    @Test void refreshedTokensAndSpoofedIpHeadersCannotResetTheSameUsersQuota() throws Exception {
        UUID subject=UUID.randomUUID();
        when(accounts.account(subject)).thenReturn(Optional.of(new AccountRepository.Account(UUID.randomUUID(),"가상 검사","USER",false)));
        for(int i=0;i<3;i++) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/me")).header("Authorization","Bearer "+tokens.token(subject)).header("X-Forwarded-For","203.0.113."+i).GET().build();
            assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/me")).header("Authorization","Bearer "+tokens.token(subject)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow())).isBetween(1,60);
        verify(accounts,times(3)).account(subject);
        assertThat(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/liveness")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }
    private String photoPath() { return "/v1/shelter-admin/dogs/"+dog+"/photos"; }
    private String seedPath() { return "/v1/shelter-admin/dogs/"+dog+"/styled-seed-examples"; }
    private byte[] seedMultipart(byte[] metadata,byte[] png,List<String> names) throws IOException {
        var out=new ByteArrayOutputStream();
        out.write("--boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(metadata);out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        for(String name:names) {
            out.write(("--boundary\r\nContent-Disposition: form-data; name=\""+name+"\"; filename=\""+name+".png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(png);out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write("--boundary--\r\n".getBytes(StandardCharsets.UTF_8));return out.toByteArray();
    }
    private HttpResponse<String> send(String path,String token,String type,byte[] bytes,boolean chunked) throws Exception {
        var publisher=chunked?HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(bytes)):HttpRequest.BodyPublishers.ofByteArray(bytes);
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type",type).POST(publisher);
        if(token!=null) builder.header("Authorization","Bearer "+token);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private byte[] multipart(byte[] file,String metadata) throws IOException {
        var out=new ByteArrayOutputStream();
        out.write(("--boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n"+metadata+"\r\n--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(file);out.write("\r\n--boundary--\r\n".getBytes(StandardCharsets.UTF_8));return out.toByteArray();
    }
}
