package org.shelterconnect.api.asset;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class PixelLabClient implements AssetProvider {
    private final AssetProperties properties;
    private final JsonMapper json;
    private final HttpClient client;
    private final URI base;
    @Autowired public PixelLabClient(AssetProperties properties, JsonMapper json) {
        this(properties,json,AssetHttp.client(),URI.create("https://api.pixellab.ai/v2/"));
    }
    PixelLabClient(AssetProperties properties, JsonMapper json, HttpClient client, URI base) {
        this.properties=properties; this.json=json; this.client=client; this.base=base;
    }
    public UUID submit(AssetAction action, byte[] source) {
        properties.requireEnabled();
        Map<String,Object> image=Map.of("type","base64","format","png","base64",Base64.getEncoder().encodeToString(source));
        Object body;
        if (action==AssetAction.BASE) {
            var size=SpriteNormalizer.dimensions(source,1024);
            body=Map.of("description",AssetAction.BASE_PROMPT,"image_size",Map.of("width",64,"height",64),
                "no_background",true,"reference_images",List.of(Map.of("image",image,
                    "size",Map.of("width",size[0],"height",size[1]),"usage_description","Dog appearance only. Ignore text, people, background, toys and other animals.")));
        } else body=Map.of("reference_image",image,"reference_image_size",Map.of("width",64,"height",64),
            "image_size",Map.of("width",64,"height",64),"action",action.prompt(),"no_background",true,
            "view","low top-down","direction","east");
        JsonNode response=send(action==AssetAction.BASE?"generate-image-v2":"animate-with-text-v2",body,true);
        try { return UUID.fromString(response.path("background_job_id").asText()); }
        catch (RuntimeException e) { throw new Failure("PROVIDER_ACK_INVALID",true); }
    }
    public Poll poll(UUID id) {
        JsonNode data=send("background-jobs/"+id,null,false);
        String status=data.path("status").asText();
        if (Set.of("processing","queued","pending").contains(status)) return new Poll("WAITING",List.of());
        if (status.equals("failed")) return new Poll("FAILED",List.of());
        if (!status.equals("completed")) throw new Failure("PROVIDER_RESULT_INVALID",false);
        JsonNode images=data.path("last_response").path("images");
        if (!images.isArray() || images.isEmpty() || images.size()>16) throw new Failure("PROVIDER_RESULT_INVALID",false);
        List<byte[]> frames=new ArrayList<>();
        for (JsonNode image: images) {
            String b64=image.path("base64").asText();
            if (b64.startsWith("data:image/png;base64,")) b64=b64.substring(22);
            if (b64.length()>100_000) throw new Failure("PROVIDER_RESULT_INVALID",false);
            try { frames.add(Base64.getDecoder().decode(b64)); }
            catch (IllegalArgumentException e) { throw new Failure("PROVIDER_RESULT_INVALID",false); }
        }
        return new Poll("COMPLETED",List.copyOf(frames));
    }
    private JsonNode send(String path,Object body,boolean submitting) {
        try {
            var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(45))
                .header("Authorization","Bearer "+properties.apiKey).header("Accept","application/json");
            if (body==null) request.GET();
            else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            var response=AssetHttp.send(client,request.build(),4*1024*1024);
            if (response.status()<200 || response.status()>=300) {
                boolean rejected=Set.of(400,401,402,403,404,422,429).contains(response.status());
                throw new Failure("PIXELLAB_HTTP_"+response.status(),submitting&&!rejected);
            }
            return json.readTree(response.body());
        } catch (Failure e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure("PIXELLAB_INTERRUPTED",submitting); }
        catch (Exception e) { throw new Failure("PIXELLAB_CONNECTION",submitting); }
    }
}
