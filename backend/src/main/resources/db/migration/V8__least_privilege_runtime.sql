-- The application is not a schema owner and cannot grant roles or bypass RLS.
-- Credentials are provisioned separately; no password belongs in migrations.
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='shelter_runtime') THEN
        CREATE ROLE shelter_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
    ELSIF EXISTS (SELECT FROM pg_roles WHERE rolname='shelter_runtime'
        AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit OR rolbypassrls OR rolreplication)) THEN
        RAISE EXCEPTION 'shelter_runtime has unexpected elevated privileges';
    END IF;
    IF EXISTS (SELECT FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='shelter_runtime')) THEN
        RAISE EXCEPTION 'shelter_runtime must not be a member of other database roles';
    END IF;
END $$;
GRANT USAGE ON SCHEMA shelter TO shelter_runtime;

-- Account/approval/affiliation authority stays with the database administrator.
GRANT SELECT ON shelter.app_users, shelter.shelters, shelter.shelter_memberships TO shelter_runtime;
GRANT INSERT(display_name,auth_provider,auth_subject) ON shelter.app_users TO shelter_runtime;
-- Row locking requires UPDATE on at least one column. These grants do not permit
-- role changes, account reactivation, shelter approval or membership changes.
GRANT UPDATE(display_name) ON shelter.app_users TO shelter_runtime;
GRANT UPDATE(updated_at) ON shelter.shelters, shelter.shelter_memberships TO shelter_runtime;
CREATE POLICY runtime_account_read ON shelter.app_users FOR SELECT TO shelter_runtime USING (true);
CREATE POLICY runtime_account_insert ON shelter.app_users FOR INSERT TO shelter_runtime
    WITH CHECK (role='USER' AND disabled_at IS NULL AND auth_provider IS NOT NULL AND auth_subject IS NOT NULL);
CREATE POLICY runtime_account_lock ON shelter.app_users FOR UPDATE TO shelter_runtime USING (true) WITH CHECK (true);
CREATE POLICY runtime_shelter_read_lock ON shelter.shelters TO shelter_runtime USING (true) WITH CHECK (true);
CREATE POLICY runtime_membership_read_lock ON shelter.shelter_memberships TO shelter_runtime USING (true) WITH CHECK (true);

DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['dogs','dog_observations','dog_photos','dog_behavior_profiles',
        'dog_behavior_evidence','chat_sessions','chat_messages','chat_message_observations',
        'adoption_notes','asset_source_permissions','asset_photo_sources','asset_jobs','asset_steps',
        'asset_submissions','behavior_suggestions','photo_upload_requests'] LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON shelter.%I TO shelter_runtime',t);
        EXECUTE format('CREATE POLICY runtime_server_access ON shelter.%I TO shelter_runtime USING (true) WITH CHECK (true)',t);
    END LOOP;
END $$;
-- Only replaced evidence links need DELETE. In particular, usage history cannot
-- be deleted or have its user/time changed by the application account.
GRANT DELETE ON shelter.dog_behavior_evidence TO shelter_runtime;
GRANT SELECT, INSERT ON shelter.ai_reply_usage TO shelter_runtime;
GRANT UPDATE(released_at) ON shelter.ai_reply_usage TO shelter_runtime;
CREATE POLICY runtime_usage_access ON shelter.ai_reply_usage TO shelter_runtime USING (true) WITH CHECK (true);
GRANT EXECUTE ON FUNCTION shelter.touch_updated_at(), shelter.keep_session_identity(),
    shelter.check_message_identity() TO shelter_runtime;

-- No privileges on auth/storage, Flyway history, schema creation or future tables.
-- Existing client-role revocations and RLS remain unchanged.
