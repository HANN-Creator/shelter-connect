package org.shelterconnect.api.catalog;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Only reviewed public map PNG/JSON files are served, never arbitrary classpath resources. */
@Component
public class MapResourceStore {
    record Asset(byte[] bytes, String contentType, String sha256) {}
    private final Map<String, Asset> assets;

    public MapResourceStore(JsonMapper json) throws IOException, NoSuchAlgorithmException {
        var loaded = new HashMap<String, Asset>();
        var index = json.readTree(new ClassPathResource("map-assets/index.json").getContentAsByteArray());
        var digest = MessageDigest.getInstance("SHA-256");
        for (var entry : index.properties()) {
            String path = entry.getKey();
            if (!safePath(path)) throw new IOException("Invalid map resource index");
            byte[] bytes = new ClassPathResource("map-assets/" + path).getContentAsByteArray();
            String sha = HexFormat.of().formatHex(digest.digest(bytes));
            String type = path.endsWith(".png") ? "image/png" : "application/json";
            if (!sha.equals(entry.getValue().path("sha256").asText())
                    || bytes.length != entry.getValue().path("bytes").asLong()
                    || !type.equals(entry.getValue().path("contentType").asText())) {
                throw new IOException("Map resource integrity check failed");
            }
            loaded.put(path, new Asset(bytes, type, sha));
        }
        assets = Map.copyOf(loaded);
    }

    Asset get(String version, String mapId, String file) {
        if (file.startsWith("/")) file = file.substring(1);
        String path = version + "/" + mapId + "/" + file;
        if (!safePath(path)) throw CatalogException.notFound();
        Asset asset = assets.get(path);
        if (asset == null) throw CatalogException.notFound();
        return asset;
    }

    private static boolean safePath(String value) {
        return value.matches("[0-9]+\\.[0-9]+\\.[0-9]+/[a-z0-9-]+/(?:[a-z0-9-]+/)*[a-z0-9-]+\\.(?:png|json)");
    }
}
