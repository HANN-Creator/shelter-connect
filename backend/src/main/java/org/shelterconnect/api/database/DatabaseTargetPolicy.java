package org.shelterconnect.api.database;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class DatabaseTargetPolicy {
    private DatabaseTargetPolicy() {}
    public static void validate(String url, String username) {
        try {
            if (url == null || !url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            URI uri=URI.create(url.substring(5));
            if(uri.getUserInfo()!=null || uri.getFragment()!=null) throw new IllegalArgumentException();
            // Disposable local PostgreSQL used by packaged CI has no TLS endpoint.
            if(Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost())) return;
            if(!uri.getHost().matches("aws-[0-9]+-[a-z0-9-]+\\.pooler\\.supabase\\.com")
                || uri.getPort()!=5432 || !"/postgres".equals(uri.getPath())
                || username==null || !username.matches("shelter_runtime\\.[a-z0-9]{20}")) throw new IllegalArgumentException();
            Map<String,String> query=new HashMap<>();
            for(String pair:uri.getRawQuery().split("&")) {
                String[] part=pair.split("=",2);
                if(part.length!=2 || query.put(URLDecoder.decode(part[0],StandardCharsets.UTF_8),
                    URLDecoder.decode(part[1],StandardCharsets.UTF_8))!=null) throw new IllegalArgumentException();
            }
            if(!query.keySet().equals(Set.of("sslmode","sslrootcert")) || !"verify-full".equals(query.get("sslmode"))
                || !Path.of(query.get("sslrootcert")).isAbsolute()) throw new IllegalArgumentException();
        } catch(RuntimeException failure) {
            throw new IllegalArgumentException("Use the runtime DB account and verify-full with the official CA certificate. Connection values were not logged.");
        }
    }
}
