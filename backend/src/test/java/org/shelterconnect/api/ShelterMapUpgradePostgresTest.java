package org.shelterconnect.api;

import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.sample.SampleDataLoader;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres")
class ShelterMapUpgradePostgresTest {
    @ParameterizedTest(name = "V28 upgrade with Supabase client roles present={0}")
    @ValueSource(booleans = {true, false})
    void v28ExistingSheltersAreLinkedOnceWithoutChangingPublicationDogsOrLegacyMap(boolean supabaseRoles) throws Exception {
        SchemaMigrationTest.migratePostgres();
        String base = System.getenv("TEST_DB_URL");
        SampleDataLoader.validateTarget(base);
        if (!base.endsWith("/shelter_test")) throw new IllegalArgumentException("Use disposable shelter_test");
        String user = System.getenv("TEST_DB_USERNAME"), password = System.getenv("TEST_DB_PASSWORD");
        String db = "shelter_map_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = base.substring(0, base.lastIndexOf('/') + 1) + db;
        try (var admin = DriverManager.getConnection(base, user, password); var ddl = admin.createStatement()) {
            ddl.execute("CREATE DATABASE " + db);
            var renamedRoles = new LinkedHashMap<String, String>();
            try {
                var before = Flyway.configure().dataSource(url,user,password).schemas("shelter").defaultSchema("shelter").target("28").load();
                before.migrate();
                try (var conn = DriverManager.getConnection(url,user,password); var sql = conn.createStatement()) {
                    sql.execute("INSERT INTO shelter.app_users(id,display_name) VALUES ('11111111-1111-4111-8111-111111111111','테스트')");
                    sql.execute("INSERT INTO shelter.shelters(id,name,region,map_key,approval_status,is_public,reviewed_by,reviewed_at) VALUES ('22222222-2222-4222-8222-222222222222','공개','테스트','legacy','APPROVED',true,'11111111-1111-4111-8111-111111111111',now())");
                    sql.execute("INSERT INTO shelter.shelters(name,region) VALUES ('비공개','테스트')");
                    sql.execute("INSERT INTO shelter.dogs(shelter_id,name,avatar_key) VALUES ('22222222-2222-4222-8222-222222222222','기존 강아지','unchanged')");
                    if (!supabaseRoles) {
                        // Ubuntu restores the V28 app DB without Supabase's client roles.
                        // Roles are cluster-wide: retain their OIDs and restore their names
                        // in finally so the disposable test cluster remains unchanged.
                        for (String role : new String[]{"anon", "authenticated"}) {
                            String hidden = "map_test_" + UUID.randomUUID().toString().replace("-", "");
                            ddl.execute("ALTER ROLE " + role + " RENAME TO " + hidden);
                            renamedRoles.put(role, hidden);
                        }
                        try (var roles = sql.executeQuery("SELECT count(*) FROM pg_roles WHERE rolname IN ('anon','authenticated')")) {
                            roles.next(); assertThat(roles.getInt(1)).isZero();
                        }
                    } else {
                        // Also prove that existing clients lose any inherited default ACLs.
                        sql.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA shelter GRANT ALL ON TABLES TO anon, authenticated");
                        sql.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA shelter GRANT EXECUTE ON FUNCTIONS TO anon, authenticated");
                    }
                    var after = Flyway.configure().dataSource(url,user,password).schemas("shelter").defaultSchema("shelter").load();
                    assertThat(after.migrate().migrationsExecuted).isEqualTo(1);
                    assertThat(after.migrate().migrationsExecuted).isZero();
                    try (var rows = sql.executeQuery("""
                            SELECT (SELECT count(*) FROM shelter.map_templates),
                                   (SELECT count(*) FROM shelter.shelter_maps),
                                   (SELECT count(*) FROM shelter.shelter_maps WHERE is_default),
                                   (SELECT count(*) FROM shelter.shelters WHERE is_public AND map_key='legacy'),
                                   (SELECT count(*) FROM shelter.shelters WHERE NOT is_public),
                                   (SELECT count(*) FROM shelter.dogs WHERE avatar_key='unchanged')
                            """)) {
                        rows.next();
                        assertThat(rows.getInt(1)).isEqualTo(3); assertThat(rows.getInt(2)).isEqualTo(6);
                        assertThat(rows.getInt(3)).isEqualTo(2); assertThat(rows.getInt(4)).isEqualTo(1);
                        assertThat(rows.getInt(5)).isEqualTo(1); assertThat(rows.getInt(6)).isEqualTo(1);
                    }
                    if (supabaseRoles) {
                        for (String role : new String[]{"anon", "authenticated"}) {
                            try (var grants = sql.executeQuery("SELECT has_table_privilege('" + role + "','shelter.map_templates','SELECT'), has_table_privilege('" + role + "','shelter.shelter_maps','INSERT'), has_function_privilege('" + role + "','shelter.assign_default_maps()','EXECUTE')")) {
                                grants.next();
                                assertThat(grants.getBoolean(1)).isFalse();
                                assertThat(grants.getBoolean(2)).isFalse();
                                assertThat(grants.getBoolean(3)).isFalse();
                            }
                        }
                    }
                }
            } finally {
                try {
                    for (var entry : renamedRoles.entrySet()) {
                        ddl.execute("ALTER ROLE " + entry.getValue() + " RENAME TO " + entry.getKey());
                    }
                } finally { ddl.execute("DROP DATABASE " + db); }
            }
        }
    }
}
