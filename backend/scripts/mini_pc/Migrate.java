import org.flywaydb.core.Flyway;

/** Separate deployment process: never starts Spring, AI, workers or HTTP. */
public final class Migrate {
    public static void main(String[] args) {
        try {
            String url = System.getenv("MIGRATION_URL");
            if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:5432/shelter_(prod_[a-z0-9_]+|migration_check_[a-f0-9]+)"))
                throw new IllegalArgumentException("Unexpected migration target");
            String owner = System.getenv("MIGRATION_SCHEMA_OWNER");
            if (owner != null && !owner.equals("shelter_migration_owner")) throw new IllegalArgumentException("Unexpected owner");
            String roleSql = owner == null ? "" : "SET ROLE shelter_migration_owner; ";
            var flyway = Flyway.configure()
                .dataSource(url, System.getenv("MIGRATION_USER"), System.getenv("MIGRATION_PASSWORD"))
                .locations("filesystem:" + System.getenv("MIGRATION_SQL"))
                .schemas("shelter").defaultSchema("shelter")
                .cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false)
                .validateMigrationNaming(true).validateOnMigrate(true)
                .group(true).mixed(false).executeInTransaction(true)
                .initSql(roleSql + "SET lock_timeout='15s'; SET statement_timeout='5min'")
                .load();
            if (args.length != 1 || !args[0].equals("migrate")) throw new IllegalArgumentException("Unexpected operation");
            flyway.migrate();
            flyway.validate();
            System.out.println("MIGRATION_OK");
        } catch (Exception failure) {
            // Provider/SQL exceptions may contain production values; no stack trace to task output.
            System.err.println("MIGRATION_FAILED: deployment stopped; SQL details withheld");
            System.exit(2);
        }
    }
}
