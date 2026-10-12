import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;

/** Explicit maintenance command, never invoked during application startup or schema migration. */
class ResetNovelProjects {
    private static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
    private static String quoted(String value) {
        if (!value.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid identifier");
        return '"' + value + '"';
    }
    public static void main(String[] args) throws Exception {
        boolean delete = args.length == 1 && args[0].equals("DELETE_ALL_NOVEL_PROJECTS");
        String schema = env("DB_SCHEMA", "novel_agent");
        quoted(schema);
        Properties properties = new Properties();
        properties.setProperty("user", env("DB_USERNAME", "novel_app"));
        properties.setProperty("password", env("DB_PASSWORD", ""));
        properties.setProperty("sslmode", env("DB_SSL_MODE", "disable"));
        properties.setProperty("currentSchema", schema);
        properties.setProperty("connectTimeout", "10");
        String url = "jdbc:postgresql://" + env("DB_HOST", "localhost") + ':' + env("DB_PORT", "5432") + '/' + env("DB_NAME", "novel_agent");
        try (Connection connection = DriverManager.getConnection(url, properties);
             var driver = GraphDatabase.driver(env("NEO4J_URI", "bolt://localhost:7687"),
                     AuthTokens.basic(env("NEO4J_USERNAME", "neo4j"), env("NEO4J_PASSWORD", "")));
             var graph = driver.session(SessionConfig.forDatabase(env("NEO4J_DATABASE", "neo4j")))) {
            driver.verifyConnectivity();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '10s'");
                String since = System.getenv("RUNNING_SINCE");
                if (since == null) throw new IllegalArgumentException("RUNNING_SINCE must be the verified backend startup time");
                String start = Instant.parse(since).toString();
                String busy = "SELECT (SELECT count(*) FROM agent_run WHERE status IN ('PENDING','RUNNING') AND started_at >= '" + start + "'::timestamptz)"
                        + " + (SELECT count(*) FROM automation_run WHERE status IN ('PENDING','RUNNING'))"
                        + " + (SELECT count(*) FROM draft_loop_run WHERE status IN ('PENDING','RUNNING'))"
                        + " + (SELECT count(*) FROM model_http_conversation WHERE lease_until > now())"
                        + " + (SELECT count(*) FROM model_codex_conversation WHERE lease_until > now())"
                        + " + (SELECT count(*) FROM snowflake_planning_run WHERE status = 'RUNNING' AND updated_at >= '" + start + "'::timestamptz)";
                try (ResultSet rows = statement.executeQuery(busy)) {
                    rows.next();
                    if (rows.getLong(1) != 0) throw new IllegalStateException("Active tasks or leases; nothing deleted");
                }
                List<String> projectIds = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery("SELECT id::text FROM novel_project ORDER BY id")) {
                    while (rows.next()) projectIds.add(rows.getString(1));
                }
                Set<String> tables = new TreeSet<>(List.of("novel_project", "outbox_event", "projection_checkpoint"));
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT c.table_name FROM information_schema.columns c
                         JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                         WHERE c.table_schema = ? AND c.column_name = 'project_id' AND t.table_type = 'BASE TABLE'
                        """)) {
                    query.setString(1, schema);
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) tables.add(rows.getString(1)); }
                }
                // Expand only declared FK dependants; RESTRICT below blocks an unexpected external dependency.
                boolean changed;
                do {
                    changed = false;
                    try (PreparedStatement query = connection.prepareStatement("""
                            SELECT parent.relname, child.relname, child_ns.nspname
                              FROM pg_constraint f JOIN pg_class parent ON parent.oid = f.confrelid
                              JOIN pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace
                              JOIN pg_class child ON child.oid = f.conrelid
                              JOIN pg_namespace child_ns ON child_ns.oid = child.relnamespace
                             WHERE f.contype = 'f' AND parent_ns.nspname = ?
                            """)) {
                        query.setString(1, schema);
                        try (ResultSet rows = query.executeQuery()) {
                            while (rows.next()) if (tables.contains(rows.getString(1))) {
                                if (!schema.equals(rows.getString(3))) throw new IllegalStateException("Cross-schema FK; nothing deleted");
                                changed |= tables.add(rows.getString(2));
                            }
                        }
                    }
                } while (changed);
                for (String table : tables) {
                    if (table.startsWith("user_") || table.contains("prompt") || table.contains("preset") || table.startsWith("flyway")) {
                        throw new IllegalStateException("Global configuration in deletion scope: " + table);
                    }
                    quoted(table);
                }
                Map<String, Long> preserved = new TreeMap<>();
                try (PreparedStatement query = connection.prepareStatement("SELECT tablename FROM pg_tables WHERE schemaname = ?")) {
                    query.setString(1, schema);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            String table = rows.getString(1);
                            if (!tables.contains(table)) preserved.put(table, count(connection, schema, table));
                        }
                    }
                }
                long graphNodes = graph.executeRead(tx -> tx.run("MATCH (n) WHERE n.projectId IN $ids RETURN count(n) AS total",
                        Map.of("ids", projectIds)).single().get("total").asLong());
                System.out.println("PROJECTS=" + projectIds.size());
                System.out.println("PROJECT_TABLES=" + String.join(",", tables));
                long projectRows = 0;
                for (String table : tables) projectRows += count(connection, schema, table);
                System.out.println("PROJECT_TABLE_ROW_TOTAL=" + projectRows);
                System.out.println("PRESERVED_TABLES=" + preserved);
                System.out.println("GRAPH_PROJECT_NODES=" + graphNodes);
                if (!delete) { connection.rollback(); System.out.println("DRY_RUN_ONLY=true"); return; }
                statement.execute("TRUNCATE " + String.join(",", tables.stream().map(table -> quoted(schema) + '.' + quoted(table)).toList()) + " RESTRICT");
                for (var entry : preserved.entrySet()) {
                    if (count(connection, schema, entry.getKey()) != entry.getValue()) throw new IllegalStateException("Preserved table changed");
                }
                connection.commit();
                // Two databases cannot share a transaction. Failure is explicit and the captured IDs remain retryable.
                try {
                    graph.executeWrite(tx -> tx.run("MATCH (n) WHERE n.projectId IN $ids DETACH DELETE n",
                            Map.of("ids", projectIds)).consume());
                } catch (RuntimeException failure) {
                    System.err.println("POSTGRES_DELETED_GRAPH_CLEANUP_FAILED_PROJECT_IDS=" + projectIds);
                    throw failure;
                }
                for (String table : tables) if (count(connection, schema, table) != 0) throw new IllegalStateException("Project rows remain: " + table);
                long remaining = graph.executeRead(tx -> tx.run("MATCH (n) WHERE n.projectId IN $ids RETURN count(n) AS total",
                        Map.of("ids", projectIds)).single().get("total").asLong());
                if (remaining != 0) throw new IllegalStateException("Graph rows remain");
                System.out.println("PROJECT_DATA_DELETED=true; GLOBAL_CONFIGURATION_PRESERVED=true; GRAPH_CLEAN=true");
            } catch (Exception failure) { connection.rollback(); throw failure; }
        }
    }
    private static long count(Connection connection, String schema, String table) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT count(*) FROM " + quoted(schema) + '.' + quoted(table))) { rows.next(); return rows.getLong(1); }
    }
}
