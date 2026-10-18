package org.shelterconnect.api.photo;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.shelterconnect.api.storage.LocalObjectStore;
import static org.shelterconnect.api.photo.PhotoTypes.*;

@Component
@ConditionalOnProperty(name="app.storage.backend",havingValue="local")
public final class LocalPhotoStorage implements PhotoStorage {
    private final PhotoStorageProperties properties;
    private final LocalObjectStore store;
    public LocalPhotoStorage(PhotoStorageProperties properties,LocalObjectStore store) { this.properties=properties; this.store=store; }
    public Map<UUID,Signed> sign(List<Stored> photos) {
        if(photos.isEmpty()) return Map.of();
        if(!properties.enabled()) throw new PhotoException(503,"PHOTO_STORAGE_NOT_CONFIGURED","사진 서비스를 준비 중이에요.");
        if(photos.size()>50) throw PhotoException.unavailable();
        Map<UUID,Signed> result=new HashMap<>();
        Set<String> paths=new HashSet<>();
        for(Stored photo:photos) {
            if(!properties.bucket().equals(photo.bucket()) || photo.key()==null || !photo.key().startsWith(photo.dogId()+"/")
                    || !photo.key().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp|gif)") || !paths.add(photo.key()))
                throw new PhotoException(503,"PHOTO_STORAGE_INVALID","사진 파일 정보를 확인하고 있어요.");
            try {
                var link=store.sign(photo.bucket(),photo.key());
                if(result.put(photo.id(),new Signed(link.url(),link.expiresAt()))!=null) throw PhotoException.unavailable();
            } catch(java.io.IOException|IllegalArgumentException e) { throw PhotoException.unavailable(); }
        }
        return Map.copyOf(result);
    }
}
