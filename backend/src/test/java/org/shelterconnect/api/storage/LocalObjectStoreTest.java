package org.shelterconnect.api.storage;

import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.Base64;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LocalObjectStoreTest {
    @TempDir Path root;
    final Clock clock=Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC);
    final String key=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[48]);
    LocalObjectStore store;
    @BeforeEach void prepare() throws Exception {
        Files.createDirectory(root.resolve("objects"));
        store=new LocalObjectStore(root,key,"https://api.example.test",clock);
    }
    @Test void persistedFilesUseExportCompatibleIdentity() throws Exception {
        byte[] bytes={1,2,3,4};
        store.put("dog-photos","dog/photo.png",bytes,100);
        assertEquals("d78befef48b183169ce7dd2a798c171683976b7fddcaba4279a1eb884b7c2260",LocalObjectStore.id("dog-photos","dog/photo.png"));
        var reopened=new LocalObjectStore(root,key,"https://api.example.test",clock);
        assertArrayEquals(bytes,reopened.get("dog-photos","dog/photo.png",100));
        assertNotEquals(LocalObjectStore.id("dog-photos","dog/photo.png"),LocalObjectStore.id("dog-assets","dog/photo.png"));
    }
    @Test void traversalAndUnknownBucketsAreRejected() {
        for(String path:new String[]{"../outside","a/../outside","/absolute","a\\b","a/%2e%2e/b","a//b","a?b","a\nb"})
            assertThrows(IllegalArgumentException.class,()->store.get("dog-photos",path,100));
        assertThrows(IllegalArgumentException.class,()->store.get("public","a.png",100));
        assertThrows(IllegalArgumentException.class,()->store.readSigned("../outside",clock.instant().getEpochSecond()+10,"x"));
    }
    @Test void signatureBindsObjectAndExpiryAndCannotBeReusedAfterExpiry() throws Exception {
        store.put("dog-photos","dog/photo.png",new byte[]{1,2},100);
        var link=store.sign("dog-photos","dog/photo.png");
        URI uri=URI.create(link.url()); String[] parts=uri.getQuery().split("&");
        long expires=Long.parseLong(parts[0].substring("expires=".length()));
        String signature=parts[1].substring("signature=".length());
        String id=uri.getPath().substring(uri.getPath().lastIndexOf('/')+1);
        assertArrayEquals(new byte[]{1,2},store.readSigned(id,expires,signature));
        assertThrows(IllegalArgumentException.class,()->store.readSigned(id,expires-1,signature));
        assertThrows(IllegalArgumentException.class,()->store.readSigned(LocalObjectStore.id("dog-assets","dog/photo.png"),expires,signature));
        assertThrows(IllegalArgumentException.class,()->store.readSigned(id,expires,"x".repeat(43)));
        var later=new LocalObjectStore(root,key,"https://api.example.test",Clock.offset(clock,Duration.ofSeconds(61)));
        assertThrows(IllegalArgumentException.class,()->later.readSigned(id,expires,signature));
    }
    @Test void rejectedWriteDoesNotReplaceExistingFile() throws Exception {
        store.put("community-media","post/image.png",new byte[]{1,2},100);
        assertThrows(IllegalArgumentException.class,()->store.put("community-media","post/image.png",new byte[101],100));
        assertArrayEquals(new byte[]{1,2},store.get("community-media","post/image.png",100));
        assertThrows(java.io.IOException.class,()->store.get("community-media","post/image.png",1));
        try(var files=Files.list(root.resolve("objects"))) { assertEquals(1,files.count()); }
    }
    @Test void unsignedAndTamperedDownloadsAreDeniedByController() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new LocalObjectController(store)).build();
        String id=LocalObjectStore.id("dog-photos","dog/photo.png");
        mvc.perform(get("/v1/storage/objects/"+id)).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/storage/objects/"+id).param("expires","1").param("signature","x".repeat(43))).andExpect(status().isForbidden());
    }
    @Test void signedImageDownloadReturnsOriginalBytesWithPrivacyHeaders() throws Exception {
        byte[] png={(byte)137,80,78,71,13,10,26,10};
        store.put("dog-photos","dog/photo.png",png,100);
        URI link=URI.create(store.sign("dog-photos","dog/photo.png").url());
        var mvc=MockMvcBuilders.standaloneSetup(new LocalObjectController(store)).build();
        mvc.perform(get(link.getRawPath()+"?"+link.getRawQuery())).andExpect(status().isOk())
                .andExpect(content().bytes(png)).andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void publicOriginAndSigningKeyAreValidated() {
        assertThrows(IllegalArgumentException.class,()->new LocalObjectStore(root,key,"http://example.test",clock));
        assertThrows(IllegalArgumentException.class,()->new LocalObjectStore(root,key,"https://example.test/path",clock));
        assertThrows(IllegalArgumentException.class,()->new LocalObjectStore(root,"short","https://example.test",clock));
    }
}
