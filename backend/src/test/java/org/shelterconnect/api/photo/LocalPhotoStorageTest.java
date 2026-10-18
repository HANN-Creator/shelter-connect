package org.shelterconnect.api.photo;

import java.nio.file.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.shelterconnect.api.storage.LocalObjectStore;
import static org.junit.jupiter.api.Assertions.*;

class LocalPhotoStorageTest {
    @TempDir Path root;
    @Test void localAdapterIssuesLinksToThePreservedObjectAndRejectsAnotherDogsPath() throws Exception {
        Files.createDirectory(root.resolve("objects"));
        String signing=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[48]);
        var objects=new LocalObjectStore(root.toString(),signing,"https://api.example.test");
        var properties=new PhotoStorageProperties(true,"","dog-photos",5,"local");
        var adapter=new LocalPhotoStorage(properties,objects);
        UUID dog=UUID.randomUUID(),photo=UUID.randomUUID();
        String path=dog+"/photo.png";
        byte[] bytes={1,2,3}; objects.put("dog-photos",path,bytes,100);
        var stored=new PhotoTypes.Stored(photo,dog,"dog-photos",path,0,"",Instant.now());
        var signed=adapter.sign(List.of(stored)).get(photo);
        assertTrue(signed.expiresAt().isAfter(Instant.now()));
        URI link=URI.create(signed.url());
        String[] query=link.getQuery().split("&");
        assertArrayEquals(bytes,objects.readSigned(link.getPath().substring(link.getPath().lastIndexOf('/')+1),
                Long.parseLong(query[0].substring(8)),query[1].substring(10)));
        var another=new PhotoTypes.Stored(photo,UUID.randomUUID(),"dog-photos",path,0,"",Instant.now());
        assertThrows(PhotoException.class,()->adapter.sign(List.of(another)));
    }
}
