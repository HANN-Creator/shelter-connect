package org.shelterconnect.api.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.NoSuchFileException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name="app.storage.backend", havingValue="local")
public final class LocalObjectController {
    private final LocalObjectStore store;
    public LocalObjectController(LocalObjectStore store) { this.store=store; }
    @GetMapping("/v1/storage/objects/{objectId}")
    public ResponseEntity<byte[]> get(@PathVariable String objectId,@RequestParam long expires,@RequestParam String signature) {
        try {
            byte[] bytes=store.readSigned(objectId,expires,signature);
            String type=URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(bytes));
            if(type==null && bytes.length>=12 && new String(bytes,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(bytes,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) type="image/webp";
            if(type==null || !java.util.Set.of("image/png","image/jpeg","image/gif","image/webp").contains(type)) type="application/octet-stream";
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer")
                    .header("Content-Disposition",type.startsWith("image/")?"inline":"attachment").body(bytes);
        } catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid storage link"); }
        catch(NoSuchFileException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        catch(IOException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); }
    }
}
