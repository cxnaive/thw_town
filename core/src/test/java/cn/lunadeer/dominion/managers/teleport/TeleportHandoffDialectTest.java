package cn.lunadeer.dominion.managers.teleport;

import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import cn.lunadeer.dominion.utils.databse.DatabaseType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: only a disposable database literally named teleport_test is accepted. */
@EnabledIfEnvironmentVariable(named = "DOMINION_TP_JDBC", matches = ".+")
class TeleportHandoffDialectTest {
    private Connection open() throws SQLException {
        return DriverManager.getConnection(System.getenv("DOMINION_TP_JDBC"),
                System.getenv().getOrDefault("DOMINION_TP_DB_USER", "root"),
                System.getenv().getOrDefault("DOMINION_TP_DB_PASSWORD", ""));
    }

    @Test
    void additiveMigrationAndAtomicHandoffOnRealDatabase() throws Exception {
        DatabaseType type = System.getenv("DOMINION_TP_JDBC").startsWith("jdbc:mysql:") ? DatabaseType.MYSQL : DatabaseType.PGSQL;
        DatabaseManager previous = DatabaseManager.instance;
        boolean created = false;
        try (Connection connection = open()) {
            assertEquals("teleport_test", connection.getCatalog(), "Refusing to use a non-test database");
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE tp_cache(uuid VARCHAR(255) PRIMARY KEY,dom_id INTEGER NOT NULL)");
                created = true;
                statement.execute("INSERT INTO tp_cache VALUES('00000000-0000-0000-0000-000000000001',1)");
            }
            new DatabaseManager(null, type.name(), "127.0.0.1", "1", "teleport_test", "unused", "") {
                @Override public Connection getConnection() throws SQLException { return open(); }
            };
            TeleportHandoffStore.migrate();
            TeleportHandoffStore.migrate();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM tp_cache")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt("dom_id"));
                assertNull(rows.getObject("request_id"));
            }
            UUID player = UUID.randomUUID();
            var value = new TeleportHandoffStore.Handoff(player, UUID.randomUUID(), 3840, 3, 60000);
            TeleportHandoffStore.save(connection, type, value);
            assertNull(TeleportHandoffStore.claim(connection, player, 2, 1));
            assertEquals(0, TeleportHandoffStore.remove(connection, player, UUID.randomUUID()));
            assertThrows(SQLException.class, () -> TeleportHandoffStore.save(connection, type,
                    new TeleportHandoffStore.Handoff(player, UUID.randomUUID(), 2, 2, 60000)));
            var pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                var a = pool.submit(() -> { start.await(); try (var conn = open()) { return TeleportHandoffStore.claim(conn, player, 3, 1); } });
                var b = pool.submit(() -> { start.await(); try (var conn = open()) { return TeleportHandoffStore.claim(conn, player, 3, 1); } });
                start.countDown();
                assertEquals(1, (a.get(10, TimeUnit.SECONDS) == null ? 0 : 1) + (b.get(10, TimeUnit.SECONDS) == null ? 0 : 1));
            } finally { pool.shutdownNow(); }
            assertNull(TeleportHandoffStore.claim(connection, player, 3, 1));
            TeleportHandoffStore.save(connection, type, value);
            assertNull(TeleportHandoffStore.claim(connection, player, 3, 60000));
            new TeleportHandoffStore().purge(60000);
            try (var statement = connection.createStatement(); var count = statement.executeQuery("SELECT COUNT(*) FROM tp_cache")) {
                assertTrue(count.next());
                assertEquals(0, count.getInt(1));
            }
        } finally {
            DatabaseManager.instance = previous;
            if (created) try (var connection = open(); var statement = connection.createStatement()) {
                statement.execute("DROP TABLE tp_cache");
            }
        }
    }
}
