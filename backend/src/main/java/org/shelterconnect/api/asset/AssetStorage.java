package org.shelterconnect.api.asset;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AssetStorage {
    void ready();
    byte[] photo(UUID dogId, String bucket, String key);
    byte[] asset(String key);
    void put(String key, byte[] png);
    void putPhoto(UUID dogId,String key,byte[] png);
    Map<String,String> sign(List<String> keys);
}
