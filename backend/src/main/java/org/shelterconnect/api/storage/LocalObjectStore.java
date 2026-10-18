package org.shelterconnect.api.storage;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Private, opaque files. Only the existing domain services can issue read links. */
@Component
@ConditionalOnProperty(name="app.storage.backend", havingValue="local")
public final class LocalObjectStore {
    public record Link(String url, Instant expiresAt) {}
    private static final Set<String> BUCKETS=Set.of("dog-photos","dog-assets","community-media");
    public static final int MAX_BYTES=5*1024*1024;
    private final Path objects;
    private final byte[] secret;
    private final String publicUrl;
    private final Clock clock;

    @Autowired
    public LocalObjectStore(@Value("${app.storage.local.root}") String root,
            @Value("${app.storage.local.signing-key}") String key,
            @Value("${app.storage.local.public-url}") String url) throws IOException {
        this(Path.of(root),key,url,Clock.systemUTC());
    }
    LocalObjectStore(Path root,String key,String url,Clock clock) throws IOException {
        if (!root.isAbsolute()) throw new IllegalArgumentException("Local storage root must be absolute");
        URI uri=URI.create(url);
        boolean https="https".equals(uri.getScheme());
        boolean loopback="http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost());
        if ((!https && !loopback) || uri.getHost()==null || uri.getUserInfo()!=null
                || !uri.getPath().isEmpty() || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("Storage public URL must be an HTTPS origin or local test origin");
        try { secret=Base64.getUrlDecoder().decode(key); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid local storage signing key"); }
        if(secret.length<32) throw new IllegalArgumentException("Storage signing key must contain at least 32 random bytes");
        this.objects=root.resolve("objects").toRealPath();
        if(!Files.isDirectory(objects)) throw new IOException("Prepared object directory missing");
        this.publicUrl=url; this.clock=clock;
    }
    public void ready(String bucket) { if(!BUCKETS.contains(bucket)) throw new IllegalArgumentException("Unknown private bucket"); }
    static String id(String bucket,String key) {
        if (!BUCKETS.contains(bucket) || key==null || key.isBlank() || key.length()>1024
                || key.contains("\\") || key.contains("%") || key.contains("?") || key.contains("#")
                || key.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid storage location");
        for(String part:key.split("/",-1)) if(part.isBlank() || part.equals(".") || part.equals(".."))
            throw new IllegalArgumentException("Invalid storage location");
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((bucket+"/"+key).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private Path file(String id) {
        if(id==null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid object ID");
        return objects.resolve(id+".blob");
    }
    private byte[] readFile(String id,int limit) throws IOException {
        Path path=file(id);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("Private object missing");
        if(limit<1 || limit>MAX_BYTES || Files.size(path)>limit) throw new IOException("Storage object exceeds size limit");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(limit+1);
            if(bytes.length>limit) throw new IOException("Storage object exceeds size limit");
            return bytes;
        }
    }
    public byte[] get(String bucket,String key,int limit) throws IOException { return readFile(id(bucket,key),limit); }
    public void put(String bucket,String key,byte[] bytes,int limit) throws IOException {
        if(bytes==null || bytes.length<1 || limit<1 || limit>MAX_BYTES || bytes.length>limit)
            throw new IllegalArgumentException("Invalid object size");
        Path target=file(id(bucket,key));
        Path temporary=Files.createTempFile(objects,"write-",".tmp");
        try {
            Files.write(temporary,bytes);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public Link sign(String bucket,String key) throws IOException {
        String id=id(bucket,key);
        if(!Files.isRegularFile(file(id),LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("Private object missing");
        long expires=clock.instant().getEpochSecond()+60;
        String signature=signature(id,expires);
        return new Link(publicUrl+"/v1/storage/objects/"+id+"?expires="+expires+"&signature="+signature,Instant.ofEpochSecond(expires));
    }
    private String signature(String id,long expires) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((id+"\n"+expires).getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public byte[] readSigned(String id,long expires,String signature) throws IOException {
        file(id);
        long now=clock.instant().getEpochSecond();
        if(expires<=now || expires>now+60 || signature==null || !signature.matches("[A-Za-z0-9_-]{43}")
                || !MessageDigest.isEqual(signature(id,expires).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("Invalid or expired storage link");
        return readFile(id,MAX_BYTES);
    }
}
