package org.shelterconnect.sample;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.filesystem.FileSystemResource;

/** One-time V1 -> V3 SQL Editor upgrade for the selected development database; never connects. */
public final class SupabaseUpgradeExporter {
	private static final List<String> FILES=List.of("V1__create_shelter_domain.sql","V2__add_reply_generation_lease.sql","V3__add_behavior_revision_and_evidence.sql");
	private static final List<String> TABLES=List.of("app_users","shelters","shelter_memberships","dogs","dog_observations","dog_photos",
		"dog_behavior_profiles","chat_sessions","chat_messages","chat_message_observations","adoption_notes");
	private SupabaseUpgradeExporter() {}
	public static void main(String[] args) throws IOException {
		if(args.length!=2)throw new IllegalArgumentException("Pass migration directory and output path");
		Path output=Path.of(args[1]);Files.createDirectories(output.toAbsolutePath().getParent());
		Files.writeString(output,export(Path.of(args[0])));
		System.out.println("Review the one-time V1 to V3 SQL and selected development project before running: "+output);
	}
	public static String export(Path directory) throws IOException {
		var sql=new StringBuilder("""
				-- User-selected shelter-connect development project only. Verify project URL before execution.
				-- Requires the exact successful V1 history. No DROP, reset, sample insert or client grants.
				-- DDL, data-preservation checks and Flyway history commit together; any error rolls back all changes.
				BEGIN;
				SET LOCAL lock_timeout = '5s';
				SET LOCAL statement_timeout = '30s';
				SET LOCAL standard_conforming_strings = on;
				LOCK TABLE shelter.flyway_schema_history IN ACCESS EXCLUSIVE MODE;
				DO $$ BEGIN
				    IF (SELECT count(*) FROM shelter.flyway_schema_history) <> 2
				       OR NOT EXISTS (SELECT 1 FROM shelter.flyway_schema_history WHERE installed_rank=0
				           AND version IS NULL AND type='SCHEMA' AND script='"shelter"' AND success)
				       OR NOT EXISTS (SELECT 1 FROM shelter.flyway_schema_history WHERE installed_rank=1
				           AND version='1' AND type='SQL' AND script='V1__create_shelter_domain.sql'
				           AND checksum=%d AND success) THEN
				        RAISE EXCEPTION 'Expected exact successful V1 history; upgrade cancelled';
				    END IF;
				END $$;
				""".formatted(checksum(directory.resolve(FILES.getFirst()))));
		// Locks protect the before/after comparison and prevent a writer from introducing data during the upgrade.
		sql.append("LOCK TABLE ").append(TABLES.stream().map(t->"shelter."+t).collect(Collectors.joining(", "))).append(" IN SHARE MODE;\n");
		sql.append("CREATE TEMP TABLE shelter_upgrade_before ON COMMIT DROP AS ").append(fingerprintQuery()).append(";\n");
		for(int i=1;i<FILES.size();i++) {
			String file=FILES.get(i);Path path=directory.resolve(file);
			sql.append(Files.readString(path)).append('\n');
			String description=file.substring(file.indexOf("__")+2,file.length()-4).replace('_',' ');
			sql.append("""
					INSERT INTO shelter.flyway_schema_history
					(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
					VALUES (%d,'%d','%s','SQL','%s',%d,current_user,
					    (extract(epoch FROM (clock_timestamp()-transaction_timestamp()))*1000)::integer,true);
					""".formatted(i+1,i+1,description,file,checksum(path)));
		}
		sql.append("CREATE TEMP TABLE shelter_upgrade_after ON COMMIT DROP AS ").append(fingerprintQuery()).append(";\n");
		sql.append("""
				DO $$ BEGIN
				    IF EXISTS (SELECT * FROM shelter_upgrade_before EXCEPT SELECT * FROM shelter_upgrade_after)
				       OR EXISTS (SELECT * FROM shelter_upgrade_after EXCEPT SELECT * FROM shelter_upgrade_before) THEN
				        RAISE EXCEPTION 'Existing data changed; upgrade cancelled';
				    END IF;
				    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='shelter' AND tablename='dog_behavior_evidence' AND rowsecurity)
				       OR (SELECT count(*) FROM shelter.dog_behavior_evidence) <> 0 THEN
				        RAISE EXCEPTION 'Unexpected behavior evidence state; upgrade cancelled';
				    END IF;
				END $$;
				COMMIT;
				SELECT version,script,checksum,success FROM shelter.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank;
				""");
		return sql.toString();
	}
	private static int checksum(Path path) {
		return ChecksumCalculator.calculate(new FileSystemResource(new Location("filesystem:"+path.toAbsolutePath().getParent()),
				path.toAbsolutePath().toString(),StandardCharsets.UTF_8,false));
	}
	private static String fingerprintQuery() {
		return TABLES.stream().map(table->{
			String row=switch(table) {
				case "chat_messages"->"to_jsonb(r)-ARRAY['generation_token','generation_expires_at','generation_attempts','generation_model','generation_response_id']";
				case "dog_behavior_profiles"->"to_jsonb(r)-'revision'";
				default->"to_jsonb(r)";
			};
			return "SELECT '"+table+"' AS table_name,count(*) AS row_count,md5(coalesce(string_agg(("+row+")::text,E'\\n' ORDER BY ("+row+")::text),'')) AS fingerprint FROM shelter."+table+" r";
		}).collect(Collectors.joining("\nUNION ALL\n"));
	}
}
