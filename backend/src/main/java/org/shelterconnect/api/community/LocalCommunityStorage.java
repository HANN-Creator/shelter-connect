package org.shelterconnect.api.community;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.shelterconnect.api.storage.LocalObjectStore;
import org.shelterconnect.api.web.FeatureException;

@Component
@ConditionalOnProperty(name="app.storage.backend",havingValue="local")
public final class LocalCommunityStorage implements CommunityStorage {
    private final LocalObjectStore store;
    private final boolean enabled;
    public LocalCommunityStorage(LocalObjectStore store,@Value("${app.community.media-enabled:false}") boolean enabled) { this.store=store; this.enabled=enabled; }
    public void checkEnabled() { if(!enabled) throw new FeatureException(503,"COMMUNITY_MEDIA_UNAVAILABLE","사진 서비스를 준비 중이에요."); }
    private void path(String key) { if(key==null || !key.matches("[a-f0-9-]{36}/[a-f0-9-]{36}/[a-f0-9]{64}\\.png")) throw FeatureException.invalid(); }
    public void put(String key,byte[] png) {
        checkEnabled(); path(key);
        try { store.put("community-media",key,png,CommunityImage.MAX_BYTES); }
        catch(java.io.IOException|IllegalArgumentException e) { throw new FeatureException(502,"COMMUNITY_MEDIA_UNAVAILABLE","사진을 저장하지 못했어요."); }
    }
    public String sign(String key) {
        checkEnabled(); path(key);
        try { return store.sign("community-media",key).url(); }
        catch(java.io.IOException|IllegalArgumentException e) { throw new FeatureException(502,"COMMUNITY_MEDIA_UNAVAILABLE","사진을 불러오지 못했어요."); }
    }
}
