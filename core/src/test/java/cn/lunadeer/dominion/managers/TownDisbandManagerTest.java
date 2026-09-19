package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.misc.DominionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TownDisbandManagerTest {
    private static final UUID OLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NEW = UUID.fromString("00000000-0000-0000-0000-000000000002");
    @TempDir Path directory;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = open();
        connection.setAutoCommit(false);
        sql("CREATE TABLE player_name(uuid TEXT PRIMARY KEY)");
        sql("INSERT INTO player_name VALUES ('" + OLD + "'), ('" + NEW + "')");
        sql("CREATE TABLE town(id INTEGER PRIMARY KEY, permission_mode TEXT, mayor_uuid TEXT)");
        sql("INSERT INTO town VALUES(17,'TEMPLATE_V1','" + OLD + "'),(99,'TEMPLATE_V1','" + NEW + "')");
        sql("CREATE TABLE dominion(id INTEGER PRIMARY KEY, owner TEXT REFERENCES player_name(uuid), "
                + "name TEXT, parent_dom_id INTEGER REFERENCES dominion(id) ON DELETE CASCADE, town_id INTEGER, "
                + "server_id INTEGER DEFAULT 3, world_uid TEXT DEFAULT '00000000-0000-0000-0000-000000000003', "
                + "x1 INTEGER DEFAULT 0,y1 INTEGER DEFAULT 0,z1 INTEGER DEFAULT 0,"
                + "x2 INTEGER DEFAULT 100,y2 INTEGER DEFAULT 100,z2 INTEGER DEFAULT 100,"
                + "join_message TEXT DEFAULT '',leave_message TEXT DEFAULT '',tp_location TEXT DEFAULT 'default',"
                + "color TEXT DEFAULT '#00BFFF',claim_kind TEXT DEFAULT 'NORMAL',attached_by TEXT,attached_at TIMESTAMP,"
                + "outpost_admin_uuid TEXT,town_paid_value BIGINT DEFAULT 0)");
        for (var flag : Flags.getAllFlags()) {
            sql("ALTER TABLE dominion ADD COLUMN \"" + flag.getFlagName() + "\" BOOLEAN DEFAULT 0");
        }
        land(-1, -1, null, false);
        sql("CREATE TABLE dominion_archive AS SELECT * FROM dominion WHERE 0");
        sql("ALTER TABLE dominion_archive ADD COLUMN archived_town_id INTEGER");
        sql("ALTER TABLE dominion_archive ADD COLUMN archived_at TIMESTAMP");
        sql("ALTER TABLE dominion_archive ADD COLUMN archive_reason TEXT");
        sql("CREATE TABLE dominion_group(id INTEGER PRIMARY KEY,dom_id INTEGER REFERENCES dominion(id) ON DELETE CASCADE,"
                + "town_group_id INTEGER,name TEXT)");
        sql("CREATE TABLE dominion_member(id INTEGER PRIMARY KEY,dom_id INTEGER REFERENCES dominion(id) ON DELETE CASCADE,"
                + "player_uuid TEXT,group_id INTEGER REFERENCES dominion_group(id) ON DELETE CASCADE,admin BOOLEAN)");
        sql("CREATE TABLE dominion_member_archive AS SELECT * FROM dominion_member WHERE 0");
        sql("ALTER TABLE dominion_member_archive ADD COLUMN town_id INTEGER");
        sql("ALTER TABLE dominion_member_archive ADD COLUMN archived_at TIMESTAMP");
        sql("ALTER TABLE dominion_member_archive ADD COLUMN archive_reason TEXT");
        sql("CREATE TABLE town_group_outpost_override(dominion_id INTEGER REFERENCES dominion(id) ON DELETE CASCADE,flag_name TEXT)");
        sql("CREATE TABLE town_trust(town_id INTEGER REFERENCES town(id) ON DELETE CASCADE,scope_type TEXT,scope_id INTEGER)");
        for (String table : List.of("town_member", "town_invite", "town_level_progress", "town_group", "town_group_flag")) {
            sql("CREATE TABLE " + table + "(town_id INTEGER REFERENCES town(id) ON DELETE CASCADE)");
            sql("INSERT INTO " + table + " VALUES(17),(99)");
        }
        sql("ALTER TABLE town_member ADD COLUMN player_uuid TEXT");
        sql("ALTER TABLE town_member ADD COLUMN role TEXT");
        connection.commit();
        connection.setAutoCommit(true);
    }

    @AfterEach
    void tearDown() throws Exception { connection.close(); }

    @Test
    void transferredLandIsExcludedFromDisbandAndKeepsItsNewOwner() throws Exception {
        land(300, -1, 17, true);
        land(400, -1, 17, false);
        assertEquals(List.of(300), DominionTransferManager.transfer(connection, 300, NEW));
        var result = TownDisbandManager.disband(connection, 17, true, OLD);
        assertEquals(List.of(400), result.deleted().stream().map(d -> d.getId()).toList());
        assertLand(300, -1, null, NEW);
        assertEquals(0, number("SELECT COUNT(*) FROM town WHERE id=17"));
        assertEquals(1, number("SELECT COUNT(*) FROM town WHERE id=99"));
    }

    @Test
    void disbandFirstNeverAllowsForceToRecreateDeletedLand() throws Exception {
        land(300, -1, 17, false);
        TownDisbandManager.disband(connection, 17, true, OLD);
        assertThrows(SQLException.class, () -> DominionTransferManager.transfer(connection, 300, NEW));
        assertEquals(0, number("SELECT COUNT(*) FROM dominion WHERE id=300"));
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_archive WHERE id=300"));
    }

    @Test
    void transferredSubAndForeignBranchKeepNearestSurvivingAncestor() throws Exception {
        land(100, -1, 99, false);
        land(200, 100, 17, false);
        land(300, 200, 17, true);
        land(310, 300, null, false);
        land(400, 200, 99, true);
        land(410, 400, null, false);
        privileges(400, 99);
        DominionTransferManager.transfer(connection, 300, NEW);
        assertLand(300, 200, null, NEW); // Transfer alone never makes overlapping roots.
        TownDisbandManager.disband(connection, 17, true, OLD);
        assertLand(300, 100, null, NEW);
        assertLand(310, 300, null, NEW);
        assertLand(400, 100, 99, OLD);
        assertLand(410, 400, null, OLD);
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_member WHERE dom_id=400"));
        assertEquals(1, number("SELECT COUNT(*) FROM town_group_outpost_override WHERE dominion_id=400"));
        assertEquals(1, number("SELECT COUNT(*) FROM town_trust WHERE town_id=99"));
    }

    @Test
    void returnPolicyArchivesAndDetachesContributedSubBeforeDeletingFoundingParent() throws Exception {
        land(200, -1, 17, false);
        sql("UPDATE dominion SET claim_kind='FOUNDING' WHERE id=200");
        land(300, 200, 17, true);
        privileges(300, 17);
        var result = TownDisbandManager.disband(connection, 17, false, OLD);
        assertEquals(List.of(200), result.deleted().stream().map(d -> d.getId()).toList());
        assertEquals(List.of(300), result.returned().stream().map(d -> d.getId()).toList());
        assertLand(300, -1, null, OLD);
        assertEquals(0, number("SELECT COUNT(*) FROM dominion_member WHERE dom_id=300"));
        assertEquals(0, number("SELECT COUNT(*) FROM town_group_outpost_override WHERE dominion_id=300"));
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_member_archive WHERE dom_id=300"));
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_archive WHERE id=300 AND parent_dom_id=200"));
    }

    @Test
    void failedTownDeletionRollsBackLandArchivesPermissionsAndReparenting() throws Exception {
        land(200, -1, 17, false);
        land(300, 200, 17, true);
        land(400, 200, 99, false);
        privileges(300, 17);
        sql("CREATE TRIGGER fail_disband BEFORE DELETE ON town WHEN OLD.id=17 "
                + "BEGIN SELECT RAISE(ABORT,'simulated final write failure'); END");
        assertThrows(SQLException.class, () -> TownDisbandManager.disband(connection, 17, false, OLD));
        assertLand(200, -1, 17, OLD);
        assertLand(300, 200, 17, OLD);
        assertLand(400, 200, 99, OLD);
        assertEquals(0, number("SELECT COUNT(*) FROM dominion_archive"));
        assertEquals(0, number("SELECT COUNT(*) FROM dominion_member_archive"));
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_member WHERE dom_id=300"));
        assertEquals(1, number("SELECT COUNT(*) FROM town_group_outpost_override WHERE dominion_id=300"));
        assertEquals(1, number("SELECT COUNT(*) FROM town_invite WHERE town_id=17"));
        assertEquals(1, number("SELECT COUNT(*) FROM town_level_progress WHERE town_id=17"));
        assertTrue(connection.getAutoCommit());
    }

    @Test
    void changedMayorCannotDisbandUsingStaleAuthorization() throws Exception {
        land(200, -1, 17, false);
        assertThrows(SQLException.class, () -> TownDisbandManager.disband(connection, 17, true, NEW));
        assertLand(200, -1, 17, OLD);
        assertEquals(0, number("SELECT COUNT(*) FROM dominion_archive"));
    }

    @Test
    void mayorAndAssistantMayDeleteExactlyOneOutpostIncludingFounding() throws Exception {
        member(OLD, "MAYOR");
        member(NEW, "ASSISTANT");
        land(100, -1, 17, false);
        land(200, 100, 17, false);
        sql("UPDATE dominion SET claim_kind='FOUNDING' WHERE id=200");
        land(300, 200, 17, true);
        land(400, 200, 99, false);
        privileges(200, 17);
        privileges(300, 17);
        var result = TownOutpostDeleteManager.delete(connection, 200, 17, NEW, false, true);
        assertEquals(200, result.getId());
        assertLand(300, 100, 17, OLD);
        assertLand(400, 100, 99, OLD);
        assertEquals(0, number("SELECT COUNT(*) FROM dominion WHERE id=200"));
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_member WHERE dom_id=300"));
        assertEquals(0, number("SELECT COUNT(*) FROM dominion_member WHERE dom_id=200"));
        assertEquals(0, number("SELECT COUNT(*) FROM town_group_outpost_override WHERE dominion_id=200"));
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_member_archive WHERE dom_id=200"));
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_archive WHERE id=200 AND archive_reason='DELETED_TOWN_OUTPOST'"));
        TownOutpostDeleteManager.delete(connection, 100, 17, OLD, false, true);
        assertLand(300, -1, 17, OLD);
        assertLand(400, -1, 99, OLD);
        assertEquals(1, number("SELECT COUNT(*) FROM town WHERE id=17"));
        assertEquals(1, number("SELECT COUNT(*) FROM town_level_progress WHERE town_id=17"));
    }

    @Test
    void residentContributorAndOutpostAdminCannotDelete() throws Exception {
        member(OLD, "RESIDENT");
        land(200, -1, 17, true);
        sql("UPDATE dominion SET outpost_admin_uuid='" + OLD + "' WHERE id=200");
        assertThrows(DominionException.class,
                () -> TownOutpostDeleteManager.delete(connection, 200, 17, OLD, false, true));
        assertThrows(DominionException.class,
                () -> TownOutpostDeleteManager.delete(connection, 200, 17, NEW, false, true));
        assertLand(200, -1, 17, OLD);
        assertEquals(0, number("SELECT COUNT(*) FROM dominion_archive"));
    }

    @Test
    void confirmationDoesNotDeleteAndRoleIsRecheckedAtExecution() throws Exception {
        member(NEW, "ASSISTANT");
        land(200, -1, 17, false);
        TownOutpostDeleteManager.delete(connection, 200, 17, NEW, false, false);
        assertLand(200, -1, 17, OLD);
        assertEquals(0, number("SELECT COUNT(*) FROM dominion_archive"));
        sql("UPDATE town_member SET role='RESIDENT' WHERE player_uuid='" + NEW + "'");
        assertThrows(DominionException.class,
                () -> TownOutpostDeleteManager.delete(connection, 200, 17, NEW, false, true));
        assertLand(200, -1, 17, OLD);
    }

    @Test
    void forceTransferInvalidatesAnEarlierOutpostDeleteConfirmation() throws Exception {
        member(OLD, "MAYOR");
        land(200, -1, 17, true);
        TownOutpostDeleteManager.delete(connection, 200, 17, OLD, false, false);
        DominionTransferManager.transfer(connection, 200, NEW);
        assertThrows(DominionException.class,
                () -> TownOutpostDeleteManager.delete(connection, 200, 17, OLD, false, true));
        assertThrows(DominionException.class,
                () -> TownOutpostDeleteManager.delete(connection, 200, 17, OLD, true, true));
        assertLand(200, -1, null, NEW);
    }

    @Test
    void administratorCanDeleteOrphanButDoesNotDisbandItsTown() throws Exception {
        land(200, -1, 123, false); // Town 123 never existed.
        assertThrows(DominionException.class,
                () -> TownOutpostDeleteManager.delete(connection, 200, 123, OLD, false, true));
        TownOutpostDeleteManager.delete(connection, 200, 123, null, true, true);
        assertEquals(0, number("SELECT COUNT(*) FROM dominion WHERE id=200"));
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_archive WHERE id=200"));
        assertEquals(2, number("SELECT COUNT(*) FROM town"));
    }

    @Test
    void failedSingleDeleteRollsBackChildReparentingAndPermissionCleanup() throws Exception {
        member(NEW, "ASSISTANT");
        land(200, -1, 17, false);
        land(300, 200, 99, false);
        privileges(200, 17);
        sql("CREATE TRIGGER fail_outpost_delete BEFORE DELETE ON dominion WHEN OLD.id=200 "
                + "BEGIN SELECT RAISE(ABORT,'simulated deletion failure'); END");
        assertThrows(SQLException.class,
                () -> TownOutpostDeleteManager.delete(connection, 200, 17, NEW, false, true));
        assertLand(200, -1, 17, OLD);
        assertLand(300, 200, 99, OLD);
        assertEquals(0, number("SELECT COUNT(*) FROM dominion_archive"));
        assertEquals(0, number("SELECT COUNT(*) FROM dominion_member_archive"));
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_member WHERE dom_id=200"));
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_group WHERE dom_id=200"));
        assertEquals(1, number("SELECT COUNT(*) FROM town_group_outpost_override WHERE dominion_id=200"));
        assertEquals(1, number("SELECT COUNT(*) FROM town_trust WHERE scope_id=200"));
    }

    private void member(UUID player, String role) throws SQLException {
        sql("INSERT INTO town_member(town_id,player_uuid,role) VALUES(17,'" + player + "','" + role + "')");
    }

    @Test
    void personalDeletionPreservesTownBranchAndItsPersonalDescendants() throws Exception {
        land(100, -1, null, false);
        land(200, 100, null, false);
        land(250, 200, null, false);
        land(300, 250, 99, true);
        land(400, 300, null, false);
        privileges(300, 99);
        var preview = PersonalDominionDeleteManager.delete(connection, 200, OLD, false, false);
        assertEquals(List.of(250, 200), preview.deleted().stream().map(d -> d.getId()).toList());
        assertEquals(List.of(300), preview.preserved().stream().map(d -> d.getId()).toList());
        assertEquals(0, number("SELECT COUNT(*) FROM dominion_archive"));
        var result = PersonalDominionDeleteManager.delete(connection, 200, OLD, false, true);
        assertEquals(List.of(250, 200), result.deleted().stream().map(d -> d.getId()).toList());
        assertLand(100, -1, null, OLD);
        assertLand(300, 100, 99, OLD);
        assertLand(400, 300, null, OLD);
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_member WHERE dom_id=300"));
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_group WHERE dom_id=300"));
        assertEquals(1, number("SELECT COUNT(*) FROM town_group_outpost_override WHERE dominion_id=300"));
        assertEquals(1, number("SELECT COUNT(*) FROM town_trust WHERE scope_id=300"));
        assertEquals(2, number("SELECT COUNT(*) FROM dominion_archive WHERE archive_reason='DELETED_PERSONAL_DOMINION'"));
        assertEquals(0, number("SELECT COUNT(*) FROM dominion_archive WHERE id IN (300,400)"));
    }

    @Test
    void personalDeleteRechecksOwnershipAndAttachmentAfterPreview() throws Exception {
        land(200, -1, null, false);
        PersonalDominionDeleteManager.delete(connection, 200, OLD, false, false);
        DominionTransferManager.transfer(connection, 200, NEW);
        assertThrows(DominionException.class,
                () -> PersonalDominionDeleteManager.delete(connection, 200, OLD, false, true));
        sql("UPDATE dominion SET town_id=17 WHERE id=200");
        assertThrows(DominionException.class,
                () -> PersonalDominionDeleteManager.delete(connection, 200, NEW, false, true));
        assertThrows(DominionException.class,
                () -> PersonalDominionDeleteManager.delete(connection, 200, NEW, true, true));
        assertLand(200, -1, 17, NEW);
    }

    @Test
    void failedPersonalDeletionRestoresWholeTreeAndArchives() throws Exception {
        land(200, -1, null, false);
        land(250, 200, null, false);
        land(300, 250, 99, false);
        privileges(250, 17);
        sql("CREATE TRIGGER fail_personal_delete BEFORE DELETE ON dominion WHEN OLD.id=200 "
                + "BEGIN SELECT RAISE(ABORT,'simulated last delete failure'); END");
        assertThrows(SQLException.class,
                () -> PersonalDominionDeleteManager.delete(connection, 200, OLD, false, true));
        assertLand(200, -1, null, OLD);
        assertLand(250, 200, null, OLD);
        assertLand(300, 250, 99, OLD);
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_member WHERE dom_id=250"));
        assertEquals(1, number("SELECT COUNT(*) FROM dominion_group WHERE dom_id=250"));
        assertEquals(0, number("SELECT COUNT(*) FROM dominion_archive"));
        assertEquals(0, number("SELECT COUNT(*) FROM dominion_member_archive"));
        assertTrue(connection.getAutoCommit());
    }

    @Test
    void newlyAttachedChildIsExcludedWhenPersonalDeleteExecutes() throws Exception {
        land(200, -1, null, false);
        land(300, 200, null, false);
        var preview = PersonalDominionDeleteManager.delete(connection, 200, OLD, false, false);
        assertEquals(2, preview.deleted().size());
        sql("UPDATE dominion SET town_id=99 WHERE id=300");
        var result = PersonalDominionDeleteManager.delete(connection, 200, OLD, false, true);
        assertEquals(List.of(200), result.deleted().stream().map(d -> d.getId()).toList());
        assertLand(300, -1, 99, OLD);
    }

    @Test
    void personalDeletionAndConcurrentForceSerializeWithoutDeletingTownBranch() throws Exception {
        land(200, -1, null, false);
        land(300, 200, 99, true);
        privileges(300, 99);
        CountDownLatch atCommit = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CountDownLatch forceStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try (Connection deletingConnection = open(); Connection transferConnection = open()) {
            var deleted = executor.submit(() -> PersonalDominionDeleteManager.delete(
                    pauseCommit(deletingConnection, atCommit, releaseCommit), 200, OLD, false, true));
            assertTrue(atCommit.await(5, TimeUnit.SECONDS));
            var transferred = executor.submit(() -> {
                forceStarted.countDown();
                return DominionTransferManager.transfer(transferConnection, 300, NEW);
            });
            assertTrue(forceStarted.await(5, TimeUnit.SECONDS));
            releaseCommit.countDown();
            assertEquals(List.of(200), deleted.get(10, TimeUnit.SECONDS).deleted().stream().map(d -> d.getId()).toList());
            assertEquals(List.of(300), transferred.get(10, TimeUnit.SECONDS));
            assertLand(300, -1, null, NEW);
            assertEquals(0, number("SELECT COUNT(*) FROM dominion_archive WHERE id=300 AND archive_reason='DELETED_PERSONAL_DOMINION'"));
        } finally {
            releaseCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDisbandWaitsForForceCommitAndDoesNotDeleteTransferredLand() throws Exception {
        land(300, -1, 17, true);
        CountDownLatch atCommit = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CountDownLatch disbandStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try (Connection transferConnection = open(); Connection disbandConnection = open()) {
            var transferred = executor.submit(() -> DominionTransferManager.transfer(
                    pauseCommit(transferConnection, atCommit, releaseCommit), 300, NEW));
            assertTrue(atCommit.await(5, TimeUnit.SECONDS));
            var disbanded = executor.submit(() -> {
                disbandStarted.countDown();
                return TownDisbandManager.disband(disbandConnection, 17, true, OLD);
            });
            assertTrue(disbandStarted.await(5, TimeUnit.SECONDS));
            releaseCommit.countDown();
            assertEquals(List.of(300), transferred.get(10, TimeUnit.SECONDS));
            assertTrue(disbanded.get(10, TimeUnit.SECONDS).deleted().isEmpty());
            assertLand(300, -1, null, NEW);
        } finally {
            releaseCommit.countDown();
            executor.shutdownNow();
        }
    }

    private Connection pauseCommit(Connection real, CountDownLatch reached, CountDownLatch release) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("commit")) {
                        reached.countDown();
                        if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("Timed out waiting for test commit.");
                    }
                    try { return method.invoke(real, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
    }

    private Connection open() throws SQLException {
        Connection result = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("town.db"));
        try (var statement = result.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return result;
    }

    private void land(int id, int parent, Integer town, boolean contributed) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO dominion(id,owner,name,parent_dom_id,town_id,attached_by) VALUES(?,?,?,?,?,?)")) {
            insert.setInt(1, id);
            insert.setString(2, OLD.toString());
            insert.setString(3, "land" + id);
            insert.setInt(4, parent);
            insert.setObject(5, town);
            insert.setString(6, contributed ? OLD.toString() : null);
            insert.executeUpdate();
        }
    }

    private void privileges(int id, int town) throws SQLException {
        sql("INSERT INTO dominion_group VALUES(" + id + "," + id + ",7,'@town')");
        sql("INSERT INTO dominion_member VALUES(" + id + "," + id + ",'" + OLD + "'," + id + ",1)");
        sql("INSERT INTO town_group_outpost_override VALUES(" + id + ",'place')");
        sql("INSERT INTO town_trust VALUES(" + town + ",'OUTPOST'," + id + ")");
    }

    private void assertLand(int id, int parent, Integer town, UUID owner) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM dominion WHERE id=" + id)) {
            assertTrue(rows.next(), "Missing land " + id);
            assertEquals(parent, rows.getInt("parent_dom_id"));
            assertEquals(town, rows.getObject("town_id"));
            assertEquals(owner.toString(), rows.getString("owner"));
        }
    }

    private int number(String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private void sql(String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
