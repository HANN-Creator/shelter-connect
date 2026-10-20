package org.shelterconnect.api.asset;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Shared by explicit generation, photo-import generation and both workers. No network calls. */
@Component
public class PublicShelterGate {
    public static final String PENDING="PUBLIC_SOURCE_LINK_REQUIRED";
    private final JdbcClient jdbc;
    public PublicShelterGate(JdbcClient jdbc) { this.jdbc=jdbc; }
    void requirePhoto(UUID photo) {
        boolean missing=jdbc.sql("""
            SELECT EXISTS (
                SELECT 1 FROM shelter.dog_photos p JOIN shelter.dogs d ON d.id=p.dog_id
                LEFT JOIN shelter.public_animal_sources a ON a.dog_id=d.id
                LEFT JOIN shelter.shelters s ON s.id=d.shelter_id
                WHERE p.id=:photo AND (
                    (a.dog_id IS NULL AND position('public animal ' in coalesce(d.introduction,''))>0)
                    OR (a.dog_id IS NOT NULL AND (a.shelter_id<>d.shelter_id
                        OR a.care_reg_no IS DISTINCT FROM s.public_care_reg_no))
                )
            )
            """).param("photo",photo).query(Boolean.class).single();
        if(missing)throw new AssetException(409,PENDING);
    }
    void waitForLink(UUID job) {
        jdbc.sql("""
            UPDATE shelter.asset_jobs SET status='QUEUED',failure_code=:code,
                next_run_at=now()+interval '5 minutes',lease_token=NULL,lease_until=NULL WHERE id=:job
            """).param("code",PENDING).param("job",job).update();
    }
}
