package org.shelterconnect.api.community;

public interface CommunityStorage {
    default void checkEnabled() {}
    void put(String key,byte[] png);
    String sign(String key);
}
