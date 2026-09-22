package org.shelterconnect.api.asset;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.shelterconnect.api.auth.SupabaseProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class SupabaseAssetStorage implements AssetStorage {
    private final AssetProperties properties;
    private final JsonMapper json;
    private final HttpClient client;
    private final String base;
    @Autowired public SupabaseAssetStorage(AssetProperties p, SupabaseProperties supabase, JsonMapper json) {
        this(p,json,AssetHttp.client(),supabase.supabaseUrl()+"/storage/v1");
    }
    SupabaseAssetStorage(AssetProperties p,JsonMapper json,HttpClient client,String base) {
        this.properties=p;this.json=json;this.client=client;this.base=base;
    }
    public void ready() { privateBucket(properties.photoBucket); privateBucket(properties.assetBucket); }
    public byte[] photo(UUID dog,String bucket,String key) {
        if(!bucket.equals(properties.photoBucket) || !key.startsWith(dog+"/")) throw AssetException.invalid();
        privateBucket(bucket);
        return request("/object/authenticated/"+bucket+"/"+path(key),"GET",null,8*1024*1024);
    }
    public byte[] asset(String key) {
        privateBucket(properties.assetBucket);
        return request("/object/authenticated/"+properties.assetBucket+"/"+path(key),"GET",null,256*1024);
    }
    public void put(String key,byte[] png) {
        privateBucket(properties.assetBucket);
        request("/object/"+properties.assetBucket+"/"+path(key),"POST",png,256*1024);
    }
    public Map<String,String> sign(List<String> keys) {
        privateBucket(properties.assetBucket);
        if(keys.isEmpty() || keys.size()>9) throw AssetException.invalid();
        keys.forEach(SupabaseAssetStorage::path);
        var body=json.writeValueAsBytes(Map.of("expiresIn",60,"paths",keys));
        JsonNode data=json.readTree(request("/object/sign/"+properties.assetBucket,"SIGN",body,256*1024));
        if(!data.isArray() || data.size()!=keys.size()) throw AssetException.unavailable();
        Map<String,String> result=new LinkedHashMap<>();
        for(JsonNode item:data) {
            String key=item.path("path").asText(), link=item.path("signedURL").asText();
            if(!keys.contains(key) || result.containsKey(key)) throw AssetException.unavailable();
            String expected="/object/sign/"+properties.assetBucket+"/"+path(key);
            URI uri;
            try { uri=URI.create(link); } catch(IllegalArgumentException e) { throw AssetException.unavailable(); }
            if(uri.isAbsolute() || !Objects.equals(uri.getRawPath(),expected) || uri.getRawFragment()!=null
                || uri.getRawQuery()==null || !uri.getRawQuery().matches("token=[A-Za-z0-9_.-]+")) throw AssetException.unavailable();
            result.put(key,base+link);
        }
        return Map.copyOf(result);
    }
    private void privateBucket(String bucket) {
        var data=json.readTree(request("/bucket/"+bucket,"GET",null,65536));
        if(!bucket.equals(data.path("id").asText()) || !data.path("public").isBoolean() || data.get("public").asBoolean()) throw AssetException.unavailable();
    }
    static String path(String key) {
        if(key.isBlank() || key.length()>1024 || key.contains("\\") || key.contains("%") || key.contains("?") || key.contains("#")) throw AssetException.invalid();
        var parts=key.split("/",-1);
        for(String part:parts) if(part.isBlank() || part.equals(".") || part.equals("..") || part.chars().anyMatch(c->c<32)) throw AssetException.invalid();
        return Arrays.stream(parts).map(p->URLEncoder.encode(p,StandardCharsets.UTF_8).replace("+","%20").replace("%7E","~")).collect(java.util.stream.Collectors.joining("/"));
    }
    private byte[] request(String path,String method,byte[] body,int limit) {
        properties.requireEnabled();
        try {
            var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("apikey",properties.storageSecret);
            if(method.equals("GET")) request.GET();
            else {
                request.header("Content-Type",method.equals("SIGN")?"application/json":"image/png");
                if(method.equals("POST")) request.header("x-upsert","true");
                request.POST(HttpRequest.BodyPublishers.ofByteArray(body));
            }
            var response=AssetHttp.send(client,request.build(),limit);
            if(response.status()<200 || response.status()>=300) throw AssetException.unavailable();
            return response.body();
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw AssetException.unavailable(); }
        catch(Exception e) { throw AssetException.unavailable(); }
    }
}
