package org.shelterconnect.api.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ShelterMapRepository {
    private final JdbcClient jdbc;

    public ShelterMapRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record MapSummary(String id, String version, String title, int width, int height,
            int sortOrder, boolean isDefault, String assetBaseUrl, String manifestUrl,
            String layoutUrl, String manifestSha256, String layoutSha256) {}

    public record MeetingScene(String id, String version, String title, int width, int height,
            String assetBaseUrl, String manifestUrl, String manifestSha256, String sourceSha256) {}

    Optional<MeetingScene> meetingScene(String id, String version) {
        return jdbc.sql("""
                SELECT id,version,title,width,height,source_sha256,
                       files->'manifest.json'->>'sha256' AS manifest_sha
                FROM shelter.map_templates
                WHERE id=:id AND version=:version AND NOT auto_assign
                  AND manifest->>'purpose'='dog_meeting'
                """).param("id", id).param("version", version).query((rs, row) -> {
                    String base = "/v1/map-assets/" + rs.getString("version") + "/" + rs.getString("id") + "/";
                    return new MeetingScene(rs.getString("id"), rs.getString("version"), rs.getString("title"),
                            rs.getInt("width"), rs.getInt("height"), base, base + "manifest.json",
                            rs.getString("manifest_sha"), rs.getString("source_sha256"));
                }).optional();
    }

    List<MapSummary> maps(UUID shelterId) {
        return jdbc.sql("""
                SELECT m.id,m.version,m.title,m.width,m.height,sm.sort_order,sm.is_default,
                       m.files->'manifest.json'->>'sha256' AS manifest_sha,
                       m.files->'map/layout.json'->>'sha256' AS layout_sha
                FROM shelter.shelter_maps sm
                JOIN shelter.map_templates m ON m.id=sm.map_id AND m.version=sm.map_version
                JOIN shelter.shelters s ON s.id=sm.shelter_id
                WHERE s.id=:id AND s.approval_status='APPROVED' AND s.is_public
                ORDER BY sm.sort_order,m.id
                """).param("id", shelterId).query((rs, row) -> {
                    String base = "/v1/map-assets/" + rs.getString("version") + "/" + rs.getString("id") + "/";
                    return new MapSummary(rs.getString("id"), rs.getString("version"), rs.getString("title"),
                            rs.getInt("width"), rs.getInt("height"), rs.getInt("sort_order"), rs.getBoolean("is_default"),
                            base, base + "manifest.json", base + "map/layout.json",
                            rs.getString("manifest_sha"), rs.getString("layout_sha"));
                }).list();
    }
}
