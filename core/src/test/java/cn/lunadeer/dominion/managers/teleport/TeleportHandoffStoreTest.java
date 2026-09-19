package cn.lunadeer.dominion.managers.teleport;

import cn.lunadeer.dominion.utils.databse.DatabaseType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TeleportHandoffStoreTest {
    @TempDir Path directory;
    private Connection connection;
    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void setup() throws Exception {
        connection = open();
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tp_cache(uuid TEXT PRIMARY KEY,dom_id INTEGER NOT NULL,"
                    + "request_id TEXT,target_server_id INTEGER,expires_at BIGINT)");
        }
    }

    private Connection open() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("test.db")); }
    @AfterEach void close() throws Exception { connection.close(); }

    private TeleportHandoffStore.Handoff value(long expiry) {
        return new TeleportHandoffStore.Handoff(player, UUID.randomUUID(), 3840, 3, expiry);
    }

    @Test
    void intermediateServerDoesNotConsumeTargetRecord() throws Exception {
        var value = value(60000);
        TeleportHandoffStore.save(connection, DatabaseType.SQLITE, value);
        assertNull(TeleportHandoffStore.claim(connection, player, 2, 1000));
        assertEquals(value, TeleportHandoffStore.claim(connection, player, 3, 1000));
        assertNull(TeleportHandoffStore.claim(connection, player, 3, 1000));
    }

    @Test
    void expiredAndLegacyRecordsNeverExecute() throws Exception {
        TeleportHandoffStore.save(connection, DatabaseType.SQLITE, value(1000));
        assertNull(TeleportHandoffStore.claim(connection, player, 3, 1000));
        try (var statement = connection.createStatement()) {
            statement.execute("UPDATE tp_cache SET request_id=NULL,expires_at=NULL,target_server_id=NULL");
        }
        assertNull(TeleportHandoffStore.claim(connection, player, 3, 0));
    }

    @Test
    void staleCleanupAndStaleWriterCannotOverwriteNewRequest() throws Exception {
        var old = value(60000);
        var next = value(61000);
        TeleportHandoffStore.save(connection, DatabaseType.SQLITE, next);
        assertEquals(0, TeleportHandoffStore.remove(connection, player, old.requestId()));
        assertThrows(SQLException.class, () -> TeleportHandoffStore.save(connection, DatabaseType.SQLITE, old));
        assertEquals(next, TeleportHandoffStore.claim(connection, player, 3, 1));
    }

    @Test
    void simultaneousArrivalHasExactlyOneWinner() throws Exception {
        var value = value(60000);
        TeleportHandoffStore.save(connection, DatabaseType.SQLITE, value);
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); try (var conn = open()) { return TeleportHandoffStore.claim(conn, player, 3, 1); } });
            var second = pool.submit(() -> { start.await(); try (var conn = open()) { return TeleportHandoffStore.claim(conn, player, 3, 1); } });
            start.countDown();
            assertEquals(1, (first.get(5, TimeUnit.SECONDS) == null ? 0 : 1) + (second.get(5, TimeUnit.SECONDS) == null ? 0 : 1));
        } finally { pool.shutdownNow(); }
    }
}
