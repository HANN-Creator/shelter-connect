package org.shelterconnect.api.asset;

import java.io.IOException;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.shelterconnect.api.auth.ShelterAccessService;
import org.shelterconnect.api.catalog.CatalogResponses.Item;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v1/shelter-admin/dogs/{dogId}/photos")
public class PhotoUploadController {
    private final PhotoUploadStore store;private final AssetStorage storage;private final ShelterAccessService access;
    public PhotoUploadController(PhotoUploadStore store,AssetStorage storage,ShelterAccessService access) { this.store=store;this.storage=storage;this.access=access; }
    @PostMapping(consumes="multipart/form-data")
    public Item<Map<String,Object>> upload(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,
                                          @RequestPart("metadata") JsonNode metadata,@RequestPart("file") MultipartFile file) throws IOException {
        UUID subject=UUID.fromString(jwt.getSubject()),dog=AssetInput.id(dogId);access.requireDog(subject,dog);
        if(file.getSize()>8*1024*1024) throw new AssetException(413,"PHOTO_TOO_LARGE");
        byte[] png=PhotoUploadImage.normalize(file.getBytes());var upload=store.begin(subject,dog,metadata,AssetRigService.sha256(png));
        if(upload.completed()) return new Item<>(store.complete(subject,upload));
        try { storage.putPhoto(dog,upload.key(),png);return new Item<>(store.complete(subject,upload)); }
        catch(RuntimeException e) { store.release(upload);throw e; }
    }
    @GetMapping
    public PhotoUploadStore.Page list(@AuthenticationPrincipal Jwt jwt,@PathVariable String dogId,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return store.list(UUID.fromString(jwt.getSubject()),AssetInput.id(dogId),limit,cursor);
    }
}
