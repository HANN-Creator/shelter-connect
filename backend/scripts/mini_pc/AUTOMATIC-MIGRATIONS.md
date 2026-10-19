# WIN-05: automatic forward DB migrations

Normal Windows boot keeps `DB_MIGRATE=false`. Only the single-instance deployment
task runs Flyway, in a separate JVM that never starts HTTP, AI or asset workers.

On a tested `main` release with new `V<number>__description.sql` files:

1. Verify GitHub build/security/release provenance, artifact hashes and unchanged
   installed dependency pins. Existing migration files cannot change/disappear.
2. Require the one-time local DPAPI-protected `shelter_migrator` configuration.
3. Create the normal encrypted production backup; hold the shared backup/deploy lock.
4. Dump `shelter`, restore into a randomly named private temporary database, and run
   the candidate release's Flyway/JDBC libraries with all pending migrations grouped
   in one transaction. Validate Flyway history and foreign keys.
5. Start the candidate against that database as the normal `shelter_runtime` account,
   with Hibernate validation, AI/storage generation/workers OFF, loopback port 18083.
   Require readiness UP, terminate it, then drop only that random temporary database.
6. Stop the public tunnel and backend. Retain a final frozen application dump in the
   protected release/migration directory so writes since step 3 are recoverable.
7. Run the same Flyway transaction against the exact configured production app DB.
   No Auth database migration is performed. Keep app runtime privileges unchanged.
8. Switch code, require local/public readiness and signed existing-file downloads.

The installer uses the local postgres administrator password once to create a
separate schema-owner role. It does not retain that administrator password. The new
role is NOSUPERUSER/NOCREATEROLE/NOREPLICATION/NOBYPASSRLS, with CREATEDB solely for
temporary rehearsals. It can SET ROLE to the existing NOLOGIN shelter_migration_owner; existing ownership and SECURITY DEFINER execution identity stay unchanged. The normal runtime role
receives no DDL privileges. Future objects default to SELECT grants for the existing
complete-backup account. New migration authors must explicitly grant appropriate
runtime access and RLS policies, following the existing migrations.

Only reviewed repository SQL is trusted. The conservative SQL guard is not a SQL
parser/security sandbox. Nontransactional operations, explicit commit/rollback,
callbacks, script configuration and non-integer/repeatable versions stop automation.
Use append-only, backward-compatible changes; data deletion cannot be made safe just
by calling it a migration. Separate destructive changes into a reviewed maintenance.

If rehearsal fails, existing production stays running. Once production migration is
attempted, an uncertain commit or failed candidate enters `migration_recovery_required`
and keeps public service stopped. Never restore an older DB automatically or start
old code blindly against changed schema. Failed commits are not retried every poll.
Inspect Flyway history and the frozen dump; prefer a forward repair preserving current
data. Auth and files are not rolled back. This failure requires operator attention.

Validation: unit tests exercise ordering, failures before stopping, refusal to roll
back old code after production schema changes, immutable history and unsafe SQL.
CI additionally creates a disposable PostgreSQL DB, proves pending DDL AND Flyway
history roll back together on a later failing migration, then proves successful
application and idempotency. No production V25 is added merely for testing.

Host activation is a separate one-time administrator setup after CI. Source changes
alone do not alter the protected installed deployment tools or production schema.
Notion integration is unavailable; external card/status synchronization is pending.
