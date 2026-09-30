package org.shelterconnect.api.community;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.shelterconnect.api.auth.SupabaseProperties;
import org.shelterconnect.api.web.FeatureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SupabaseCommunityStorage implements CommunityStorage {
    private final String base,bucket,secret;private final boolean enabled;private final JsonMapper json;private final HttpClient client;
    @Autowired public SupabaseCommunityStorage(SupabaseProperties supabase,Environment env,JsonMapper json) {
        this(supabase.supabaseUrl()+"/storage/v1",env.getProperty("app.community.media-bucket","community-media"),env.getProperty("app.community.storage-secret",""),env.getProperty("app.community.media-enabled",Boolean.class,false),json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    SupabaseCommunityStorage(String base,String bucket,String secret,boolean enabled,JsonMapper json,HttpClient client){this.base=base;this.bucket=bucket;this.secret=secret;this.enabled=enabled;this.json=json;this.client=client;}
    public void checkEnabled(){if(!enabled || !bucket.matches("[a-z0-9][a-z0-9_-]{0,62}") || !secret.startsWith("sb_secret_"))throw unavailable();}
    public void put(String key,byte[] png) {
        path(key);if(png.length<1||png.length>CommunityImage.MAX_BYTES)throw FeatureException.invalid();privateBucket();
        request("/object/"+bucket+"/"+key,"image/png",png);
    }
    public String sign(String key) {
        path(key);privateBucket();var result=json.readTree(request("/object/sign/"+bucket+"/"+key,"application/json",json.writeValueAsBytes(Map.of("expiresIn",60))));
        String value=result.path("signedURL").asString();URI uri;
        try {uri=URI.create(value);}catch(Exception ex){throw unavailable();}
        if(uri.isAbsolute()||!Objects.equals(uri.getRawPath(),"/object/sign/"+bucket+"/"+key)||uri.getRawFragment()!=null||uri.getRawQuery()==null||!uri.getRawQuery().matches("token=[A-Za-z0-9_.-]+"))throw unavailable();
        return base+value;
    }
    private void privateBucket() {
        checkEnabled();
        var info=json.readTree(request("/bucket/"+bucket,null,null));
        if(!bucket.equals(info.path("id").asString())||!info.path("public").isBoolean()||info.path("public").asBoolean())throw unavailable();
    }
    private static void path(String key){if(key==null||!key.matches("[a-f0-9-]{36}/[a-f0-9-]{36}/[a-f0-9]{64}\\.png"))throw FeatureException.invalid();}
    private byte[] request(String path,String contentType,byte[] body) {
        if(!enabled)throw unavailable();
        try {
            var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15)).header("apikey",secret);
            if(body==null)request.GET();else {request.header("Content-Type",contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));if(contentType.equals("image/png"))request.header("x-upsert","true");}
            var future=client.sendAsync(request.build(),info->new LimitedBody());
            HttpResponse<byte[]> response;
            try{response=future.get(15,TimeUnit.SECONDS);}catch(Exception ex){future.cancel(true);throw ex;}
            if(response.statusCode()<200||response.statusCode()>=300)throw unavailable();return response.body();
        }catch(InterruptedException ex){Thread.currentThread().interrupt();throw unavailable();}catch(Exception ex){throw unavailable();}
    }
    private static FeatureException unavailable(){return new FeatureException(503,"COMMUNITY_STORAGE_UNAVAILABLE","사진 저장소를 잠시 사용할 수 없어요.");}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();private Flow.Subscription subscription;private int size;
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
        public void onNext(List<ByteBuffer> values){for(var b:values)size+=b.remaining();if(size>65536){subscription.cancel();delegate.onError(new IllegalStateException("Storage response too large"));}else delegate.onNext(values);}
        public void onError(Throwable ex){delegate.onError(ex);}public void onComplete(){delegate.onComplete();}
    }
}
