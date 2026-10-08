package org.shelterconnect.api.asset;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipFile;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class StyledPixelLabClient implements StyledAssetProvider {
    private final AssetProperties properties;private final JsonMapper json;private final HttpClient http;private final URI base;
    @Autowired public StyledPixelLabClient(AssetProperties properties,JsonMapper json) {
        this(properties,json,AssetHttp.client(),URI.create("https://api.pixellab.ai/v2/"));
    }
    StyledPixelLabClient(AssetProperties properties,JsonMapper json,HttpClient http,URI base) { this.properties=properties;this.json=json;this.http=http;this.base=base; }
    public UUID submit(boolean character,JsonNode payload) {
        return submitEndpoint(character?"create-character-pro":"animate-pixminimax",payload);
    }
    public UUID editAnimation(JsonNode payload) { return submitEndpoint("edit-animation-v2",payload); }
    public UUID editSeedEyes(JsonNode payload) { return submitEndpoint("inpaint-v3",payload); }
    public JsonNode pollSeedEyes(UUID id) {
        var response=json.readTree(send("background-jobs/"+id,null,4_000_000));String state=response.path("status").asText();
        if(Set.of("processing","queued","pending").contains(state))return json.valueToTree(Map.of("status","WAITING"));
        if(state.equals("failed"))return json.valueToTree(Map.of("status","FAILED"));
        if(!state.equals("completed"))throw new AssetProvider.Failure("PROVIDER_RESULT_INVALID",false);
        String encoded=response.at("/last_response/image/base64").asText();
        if(encoded.startsWith("data:image/png;base64,"))encoded=encoded.substring(22);
        if(encoded.length()>140_000)throw StyledSeedEyeRepair.invalid();
        byte[] raw;try{raw=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){throw StyledSeedEyeRepair.invalid();}
        StyledSeedEyeRepair.rawStrip(raw);
        var result=json.createObjectNode().put("status","COMPLETED").put("eyeSheet",Base64.getEncoder().encodeToString(raw));
        var usage=response.path("usage");if(usage.isMissingNode() || usage.isNull())usage=response.at("/last_response/billing_usage");
        if(!usage.isMissingNode() && !usage.isNull())result.set("usage",usage);return result;
    }
    private UUID submitEndpoint(String endpoint,JsonNode payload) {
        properties.requireEnabled();
        var response=json.readTree(send(endpoint,json.writeValueAsBytes(payload),4_000_000));
        try { return UUID.fromString(response.path("background_job_id").asText()); }
        catch(Exception e) { throw new AssetProvider.Failure("PROVIDER_ACK_INVALID",true); }
    }
    public JsonNode poll(UUID id,boolean character) {
        var response=json.readTree(send("background-jobs/"+id,null,4_000_000));
        String state=response.path("status").asText();
        if(Set.of("processing","queued","pending").contains(state)) return json.valueToTree(Map.of("status","WAITING"));
        if(state.equals("failed")) return json.valueToTree(Map.of("status","FAILED"));
        if(!state.equals("completed")) throw new AssetProvider.Failure("PROVIDER_RESULT_INVALID",false);
        if(!character) {
            var images=response.at("/last_response/images");
            if(!images.isArray() || images.size()!=9) throw new AssetException(422,"STYLED_FRAME_COUNT_INVALID");
            var encoded=new ArrayList<String>();
            for(var image:images) encoded.add(Base64.getEncoder().encodeToString(decode(image.path("base64").asText())));
            var completed=json.createObjectNode().put("status","COMPLETED");completed.set("frames",json.valueToTree(encoded));
            var usage=response.path("usage");if(usage.isMissingNode() || usage.isNull())usage=response.at("/last_response/billing_usage");
            if(!usage.isMissingNode() && !usage.isNull())completed.set("usage",usage);
            return completed;
        }
        UUID characterId;
        try { characterId=UUID.fromString(response.at("/last_response/character_id").asText()); }
        catch(Exception e) { throw new AssetProvider.Failure("PROVIDER_RESULT_INVALID",false); }
        byte[] archive=send("characters/"+characterId+"/zip",null,20_000_000);
        var rotations=new LinkedHashMap<String,String>();Path temp=null;
        try {
            temp=Files.createTempFile("styled-character-",".zip");Files.write(temp,archive);
            try(var zip=new ZipFile(temp.toFile())) {
                if(zip.size()>256)throw new IOException("Archive entry limit");
                for(String direction:StyledSpriteCodec.DIRECTIONS) {
                    var entries=zip.stream().filter(e->e.getName().equals("rotations/"+direction+".png") || e.getName().endsWith("/rotations/"+direction+".png")).toList();
                    if(entries.size()!=1 || entries.getFirst().getSize()>100_000)throw new IOException("Rotation entry");
                    try(var in=zip.getInputStream(entries.getFirst())) {
                        byte[] bytes=in.readNBytes(100_001);StyledSpriteCodec.nativeFrame(bytes);
                        rotations.put(direction,Base64.getEncoder().encodeToString(bytes));
                    }
                }
            }
        } catch(IOException e) { throw new AssetException(422,"STYLED_ARCHIVE_INVALID"); }
        finally { if(temp!=null)try { Files.deleteIfExists(temp); } catch(IOException ignored) { } }
        if(!rotations.keySet().equals(new HashSet<>(StyledSpriteCodec.DIRECTIONS))) throw new AssetException(422,"STYLED_ARCHIVE_INVALID");
        return json.valueToTree(Map.of("status","COMPLETED","directions",rotations));
    }
    static byte[] decode(String encoded) {
        if(encoded.startsWith("data:image/png;base64,")) encoded=encoded.substring(22);
        if(encoded.length()>140_000) throw new AssetException(422,"STYLED_FRAME_INVALID");
        try { byte[] bytes=Base64.getDecoder().decode(encoded);StyledSpriteCodec.nativeFrame(bytes);return bytes; }
        catch(IllegalArgumentException e) { throw new AssetException(422,"STYLED_FRAME_INVALID"); }
    }
    private byte[] send(String path,byte[] body,int max) {
        boolean post=body!=null;
        try {
            var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(45))
                .header("Authorization","Bearer "+properties.apiKey);
            if(post)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));else request.GET();
            var response=AssetHttp.send(http,request.build(),max);
            if(response.status()<200 || response.status()>=300) throw new AssetProvider.Failure("PIXELLAB_HTTP_"+response.status(),post&&!Set.of(400,401,402,403,404,422,429).contains(response.status()));
            return response.body();
        } catch(AssetProvider.Failure e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new AssetProvider.Failure("PIXELLAB_INTERRUPTED",post); }
        catch(Exception e) { throw new AssetProvider.Failure("PIXELLAB_CONNECTION",post); }
    }
}
