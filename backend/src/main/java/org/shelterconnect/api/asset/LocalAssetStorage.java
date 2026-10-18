package org.shelterconnect.api.asset;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.shelterconnect.api.storage.LocalObjectStore;

@Component
@ConditionalOnProperty(name="app.storage.backend",havingValue="local")
public final class LocalAssetStorage implements AssetStorage {
    private final AssetProperties properties;
    private final LocalObjectStore store;
    public LocalAssetStorage(AssetProperties properties,LocalObjectStore store) { this.properties=properties; this.store=store; }
    public void ready() { properties.requireEnabled(); store.ready(properties.photoBucket); store.ready(properties.assetBucket); }
    public byte[] photo(UUID dog,String bucket,String key) {
        if(!bucket.equals(properties.photoBucket) || key==null || !key.startsWith(dog+"/")) throw AssetException.invalid();
        return read(bucket,key,PhotoUploadImage.MAX_BYTES);
    }
    public byte[] asset(String key) { return read(properties.assetBucket,key,256*1024); }
    private byte[] read(String bucket,String key,int limit) {
        ready();
        try { return store.get(bucket,key,limit); }
        catch(java.io.IOException|IllegalArgumentException e) { throw AssetException.unavailable(); }
    }
    public void put(String key,byte[] png) { write(properties.assetBucket,key,png,256*1024); }
    public void putPhoto(UUID dog,String key,byte[] png) {
        if(key==null || !key.matches(dog+"/uploads/[a-f0-9-]{36}/[a-f0-9]{64}\\.png")) throw AssetException.invalid();
        write(properties.photoBucket,key,png,PhotoUploadImage.MAX_BYTES);
    }
    private void write(String bucket,String key,byte[] bytes,int limit) {
        ready();
        try { store.put(bucket,key,bytes,limit); }
        catch(java.io.IOException|IllegalArgumentException e) { throw AssetException.unavailable(); }
    }
    public Map<String,String> sign(List<String> keys) {
        ready();
        if(keys.isEmpty() || keys.size()>36 || new HashSet<>(keys).size()!=keys.size()) throw AssetException.invalid();
        Map<String,String> result=new LinkedHashMap<>();
        try { for(String key:keys) result.put(key,store.sign(properties.assetBucket,key).url()); }
        catch(java.io.IOException|IllegalArgumentException e) { throw AssetException.unavailable(); }
        return Map.copyOf(result);
    }
}
