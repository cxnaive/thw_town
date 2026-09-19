package cn.lunadeer.dominion.managers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DominionTransferManagerTest {
    private static final UUID OLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NEW = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        sql("PRAGMA foreign_keys=ON");
        sql("CREATE TABLE player_name(uuid TEXT PRIMARY KEY)");
        sql("INSERT INTO player_name VALUES ('" + OLD + "'), ('" + NEW + "')");
        sql("CREATE TABLE town(id INTEGER PRIMARY KEY, permission_mode TEXT)");
        sql("CREATE TABLE dominion(id INTEGER PRIMARY KEY, owner TEXT REFERENCES player_name(uuid), "
                + "name TEXT, parent_dom_id INTEGER NOT NULL, town_id INTEGER, server_id INTEGER DEFAULT 3, "
                + "world_uid TEXT DEFAULT 'remote-world', x1 INTEGER DEFAULT 4044, "
                + "claim_kind TEXT DEFAULT 'NORMAL', attached_by TEXT, attached_at TEXT, "
                + "outpost_admin_uuid TEXT, town_paid_value BIGINT DEFAULT 0)");
        sql("CREATE TABLE dominion_archive AS SELECT * FROM dominion WHERE 0");
        sql("ALTER TABLE dominion_archive ADD COLUMN archived_town_id INTEGER");
        sql("ALTER TABLE dominion_archive ADD COLUMN archived_at TEXT");
        sql("ALTER TABLE dominion_archive ADD COLUMN archive_reason TEXT");
        sql("CREATE TABLE dominion_member(id INTEGER PRIMARY KEY, dom_id INTEGER, player_uuid TEXT, group_id INTEGER, admin BOOLEAN)");
        sql("CREATE TABLE dominion_member_archive AS SELECT * FROM dominion_member WHERE 0");
        sql("ALTER TABLE dominion_member_archive ADD COLUMN town_id INTEGER");
        sql("ALTER TABLE dominion_member_archive ADD COLUMN archived_at TEXT");
        sql("ALTER TABLE dominion_member_archive ADD COLUMN archive_reason TEXT");
        sql("CREATE TABLE dominion_group(id INTEGER PRIMARY KEY, dom_id INTEGER, town_group_id INTEGER, name TEXT)");
        sql("CREATE TABLE town_group_outpost_override(dominion_id INTEGER, flag_name TEXT)");
        sql("CREATE TABLE town_trust(town_id INTEGER, scope_type TEXT, scope_id INTEGER)");
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void orphanTownLandBecomesPersonalAndArchivesThePreviousState() throws Exception {
        seedOutpost(300, 17);
        assertEquals(List.of(300), DominionTransferManager.transfer(connection, 300, NEW));
        try (ResultSet row = query("SELECT * FROM dominion WHERE id=300")) {
            assertTrue(row.next());
            assertEquals(NEW.toString(), row.getString("owner"));
            assertNull(row.getObject("town_id"));
            assertNull(row.getString("attached_by"));
            assertNull(row.getString("attached_at"));
            assertNull(row.getString("outpost_admin_uuid"));
            assertEquals(0, row.getLong("town_paid_value"));
            assertEquals(3, row.getInt("server_id"));
            assertEquals("remote-world", row.getString("world_uid"));
            assertEquals(4044, row.getInt("x1"));
        }
        assertEquals(0, count("dominion_member"));
        assertEquals(0, count("town_group_outpost_override"));
        assertEquals(0, count("dominion_group"));
        assertEquals(1, count("town_trust")); // Other outpost scope remains.
        try (ResultSet row = query("SELECT * FROM dominion_archive")) {
            assertTrue(row.next());
            assertEquals(OLD.toString(), row.getString("owner"));
            assertEquals(17, row.getInt("town_id"));
            assertEquals(999, row.getLong("town_paid_value"));
            assertEquals("ADMIN_FORCE_TRANSFER", row.getString("archive_reason"));
        }
        assertEquals(1, count("dominion_member_archive"));
    }

    @Test
    void foundingAndSameOwnerTransfersAreAllowedWithoutDeletingTown() throws Exception {
        sql("INSERT INTO town VALUES(17, 'TEMPLATE_V1')");
        seedOutpost(300, 17);
        sql("UPDATE dominion SET claim_kind='FOUNDING' WHERE id=300");
        DominionTransferManager.transfer(connection, 300, OLD);
        assertEquals(1, count("town"));
        try (ResultSet row = query("SELECT * FROM dominion WHERE id=300")) {
            assertTrue(row.next());
            assertNull(row.getObject("town_id"));
            assertEquals("NORMAL", row.getString("claim_kind"));
            assertEquals(OLD.toString(), row.getString("owner"));
        }
    }

    @Test
    void personalChildrenMoveButTownEnclaveAndItsDescendantsDoNot() throws Exception {
        personal(20, -1);
        personal(30, 20);
        personal(31, 30);
        personal(32, 30);
        sql("UPDATE dominion SET town_id=99 WHERE id=32");
        personal(33, 32);
        assertEquals(List.of(30, 31), DominionTransferManager.transfer(connection, 30, NEW));
        try (ResultSet row = query("SELECT parent_dom_id FROM dominion WHERE id=30")) {
            assertTrue(row.next());
            assertEquals(20, row.getInt(1)); // Keep containment; do not overlap the parent as another root.
        }
        for (int id : List.of(20, 32, 33)) {
            try (ResultSet row = query("SELECT owner FROM dominion WHERE id=" + id)) {
                assertTrue(row.next());
                assertEquals(OLD.toString(), row.getString(1));
            }
        }
    }

    @Test
    void failureAfterCleanupRollsBackOwnershipPrivilegesAndArchives() throws Exception {
        seedOutpost(300, 17);
        personal(301, 300);
        sql("CREATE TRIGGER fail_transfer BEFORE UPDATE OF owner ON dominion "
                + "WHEN NEW.id=301 AND NEW.owner <> OLD.owner BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END");
        assertThrows(SQLException.class, () -> DominionTransferManager.transfer(connection, 300, NEW));
        assertEquals(0, count("dominion_archive"));
        assertEquals(0, count("dominion_member_archive"));
        assertEquals(1, count("dominion_member"));
        assertEquals(1, count("dominion_group"));
        assertEquals(1, count("town_group_outpost_override"));
        assertEquals(2, count("town_trust"));
        try (ResultSet row = query("SELECT owner,town_id FROM dominion WHERE id=300")) {
            assertTrue(row.next());
            assertEquals(OLD.toString(), row.getString(1));
            assertEquals(17, row.getInt(2));
        }
        assertTrue(connection.getAutoCommit());
    }

    @Test
    void personalPermissionsAndUnrelatedTownLandArePreserved() throws Exception {
        personal(200, -1);
        sql("INSERT INTO dominion_member VALUES(2,200,'" + OLD + "',2,1)");
        sql("INSERT INTO dominion_group VALUES(2,200,NULL,'friends')");
        seedOutpost(300, 17);
        DominionTransferManager.transfer(connection, 200, NEW);
        assertEquals(2, count("dominion_member"));
        assertEquals(2, count("dominion_group"));
        assertEquals(1, count("town_group_outpost_override"));
        assertEquals(2, count("town_trust"));
        assertEquals(0, count("dominion_member_archive"));
        try (ResultSet row = query("SELECT owner,town_id FROM dominion WHERE id=300")) {
            assertTrue(row.next());
            assertEquals(OLD.toString(), row.getString(1));
            assertEquals(17, row.getInt(2));
        }
    }

    @Test
    void missingTargetsAndVirtualRootAreNeverCreatedOrTransferred() throws Exception {
        personal(-1, -1);
        assertThrows(SQLException.class, () -> DominionTransferManager.transfer(connection, -1, NEW));
        assertThrows(SQLException.class, () -> DominionTransferManager.transfer(connection, 404, NEW));
        assertEquals(1, count("dominion"));
        assertEquals(0, count("dominion_archive"));
    }

    private void seedOutpost(int id, int town) throws SQLException {
        personal(id, -1);
        sql("UPDATE dominion SET town_id=" + town + ", attached_by='" + OLD
                + "', attached_at='2026-08-10', outpost_admin_uuid='" + OLD + "', town_paid_value=999 WHERE id=" + id);
        sql("INSERT INTO dominion_member VALUES(1," + id + ",'" + OLD + "',1,1)");
        sql("INSERT INTO dominion_group VALUES(1," + id + ",7,'@town')");
        sql("INSERT INTO town_group_outpost_override VALUES(" + id + ",'place')");
        sql("INSERT INTO town_trust VALUES(" + town + ",'OUTPOST'," + id + "), (99,'OUTPOST',999)");
    }

    private void personal(int id, int parent) throws SQLException {
        sql("INSERT INTO dominion(id,owner,name,parent_dom_id) VALUES(" + id + ",'" + OLD + "','land" + id + "'," + parent + ")");
    }

    private int count(String table) throws SQLException {
        try (ResultSet rows = query("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private ResultSet query(String sql) throws SQLException {
        var statement = connection.createStatement();
        statement.closeOnCompletion();
        return statement.executeQuery(sql);
    }

    private void sql(String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
