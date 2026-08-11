package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.api.dtos.GroupDTO;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.api.dtos.flag.PriFlag;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.misc.DominionException;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Town-level permission templates and sparse outpost overrides.
 * <p>
 * {@code town_group_id = 0} identifies the built-in {@code @town} group;
 * positive ids identify custom {@code town_group} definitions.  The legacy
 * {@code dominion_group} rows remain the effective-value compatibility cache
 * consumed by the existing API and protection pipeline.
 */
public final class TownPermissionManager {

    public static final int TOWN_DEFAULT_GROUP_ID = 0;

    @FunctionalInterface
    private interface TransactionWork<T> {
        T run(Connection connection) throws Exception;
    }

    private static <T> T transaction(TransactionWork<T> work) throws Exception {
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
    }

    /** Seed the immutable template model of a newly-created town. */
    public static void initializeNewTown(@NotNull TownDOO town) throws Exception {
        transaction(connection -> {
            lockTown(connection, town.getId());
            requireTemplateMode(connection, town.getId());
            ensureDefaultTemplate(connection, town.getId(), TOWN_DEFAULT_GROUP_ID);
            // The town row becomes visible before this initializer runs.
            // Heal any outpost that a second backend managed to attach in
            // that narrow window; a still-blocked attach will perform the
            // same sync after it acquires this town lock.
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM dominion WHERE town_id = ?")) {
                select.setInt(1, town.getId());
                try (ResultSet rows = select.executeQuery()) {
                    List<Integer> ids = new ArrayList<>();
                    while (rows.next()) ids.add(rows.getInt(1));
                    for (Integer id : ids) syncOutpostFlags(connection, town.getId(), id);
                }
            }
            return null;
        });
    }

    /**
     * Destructive one-way migration: reset every template to current group
     * defaults, remove every local override, make every mirror effective
     * value inherited, then flip the mode as the final statement.
     */
    public static void assertUpgradeAllowed(@NotNull TownDOO town,
                                            @NotNull UUID operatorUuid,
                                            boolean bypass) throws Exception {
        try (Connection connection = DatabaseManager.instance.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT mayor_uuid, permission_mode FROM town WHERE id = ?")) {
            select.setInt(1, town.getId());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Town disappeared during permission upgrade: "
                            + town.getId());
                }
                requireUpgradeAllowed(town, operatorUuid, bypass,
                        row.getString("mayor_uuid"), row.getString("permission_mode"));
            }
        }
    }

    public static int upgrade(@NotNull TownDOO town, @NotNull UUID operatorUuid,
                              boolean bypass) throws Exception {
        return transaction(connection -> {
            lockTown(connection, town.getId());
            String mayorUuid;
            String permissionMode;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT mayor_uuid, permission_mode FROM town WHERE id = ?")) {
                select.setInt(1, town.getId());
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) {
                        throw new SQLException("Town disappeared during permission upgrade: "
                                + town.getId());
                    }
                    mayorUuid = row.getString("mayor_uuid");
                    permissionMode = row.getString("permission_mode");
                }
            }
            requireUpgradeAllowed(town, operatorUuid, bypass, mayorUuid, permissionMode);

            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM town_group_flag WHERE town_id = ?")) {
                delete.setInt(1, town.getId());
                delete.executeUpdate();
            }
            ensureDefaultTemplate(connection, town.getId(), TOWN_DEFAULT_GROUP_ID);
            List<Integer> groupIds = new ArrayList<>();
            try (PreparedStatement groups = connection.prepareStatement(
                    "SELECT id FROM town_group WHERE town_id = ? ORDER BY id")) {
                groups.setInt(1, town.getId());
                try (ResultSet rows = groups.executeQuery()) {
                    while (rows.next()) groupIds.add(rows.getInt(1));
                }
            }
            for (Integer groupId : groupIds) {
                copyTemplateIfMissing(connection, town.getId(), TOWN_DEFAULT_GROUP_ID, groupId);
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM town_group_outpost_override WHERE dominion_id IN "
                            + "(SELECT id FROM dominion WHERE town_id = ?)")) {
                delete.setInt(1, town.getId());
                delete.executeUpdate();
            }

            int outposts = 0;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM dominion WHERE town_id = ? ORDER BY id")) {
                select.setInt(1, town.getId());
                try (ResultSet rows = select.executeQuery()) {
                    List<Integer> ids = new ArrayList<>();
                    while (rows.next()) ids.add(rows.getInt(1));
                    for (Integer id : ids) {
                        syncOutpostFlags(connection, town.getId(), id);
                        outposts++;
                    }
                }
            }

            int updated;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE town SET permission_mode = ? WHERE id = ? AND permission_mode = ?")) {
                update.setString(1, TownDOO.PermissionMode.TEMPLATE_V1.name());
                update.setInt(2, town.getId());
                update.setString(3, TownDOO.PermissionMode.LEGACY.name());
                updated = update.executeUpdate();
            }
            if (updated != 1) {
                throw new SQLException("Town permission mode changed concurrently: " + town.getId());
            }
            return outposts;
        });
    }

    public static void syncOutpostFlags(@NotNull TownDOO town,
                                        @NotNull DominionDOO outpost) throws Exception {
        transaction(connection -> {
            lockTown(connection, town.getId());
            requireOutpostOfTown(connection, town.getId(), outpost.getId());
            if (!isTemplateMode(connection, town.getId())) return null;
            syncOutpostFlags(connection, town.getId(), outpost.getId());
            return null;
        });
        TownGroupSyncHandler.reloadCaches();
    }

    public static void setTemplateFlag(@NotNull TownDOO town, int townGroupId,
                                       @NotNull PriFlag flag, boolean value) throws Exception {
        transaction(connection -> {
            lockTown(connection, town.getId());
            requireTemplateMode(connection, town.getId());
            requireManageableTemplateFlag(flag);
            if (townGroupId != TOWN_DEFAULT_GROUP_ID) {
                requireTownGroup(connection, town.getId(), townGroupId);
            }
            replaceValue(connection, "town_group_flag", "town_id", town.getId(),
                    townGroupId, flag.getFlagName(), value);
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM dominion WHERE town_id = ?")) {
                select.setInt(1, town.getId());
                try (ResultSet rows = select.executeQuery()) {
                    List<Integer> ids = new ArrayList<>();
                    while (rows.next()) ids.add(rows.getInt(1));
                    for (Integer id : ids) syncOutpostFlags(connection, town.getId(), id);
                }
            }
            return null;
        });
        TownGroupSyncHandler.reloadCaches();
    }

    /**
     * Route a town-managed mirror write using the authoritative database
     * mode. LEGACY updates the mirror directly; TEMPLATE_V1 records a sparse
     * override and refreshes the effective mirror value.
     */
    public static void setOutpostFlag(@NotNull DominionDOO outpost, @NotNull GroupDTO group,
                                      @NotNull PriFlag flag, boolean value) throws Exception {
        transaction(connection -> {
            Integer townId = selectOutpostTownId(connection, outpost.getId());
            Integer townGroupId = selectTownManagedGroupKey(connection, outpost.getId(), group.getId());
            if (townId == null || townGroupId == null) {
                updateMirrorFlag(connection, outpost.getId(), group.getId(), flag, value);
                return null;
            }

            lockTown(connection, townId);
            requireOutpostOfTown(connection, townId, outpost.getId());
            Integer lockedGroupId = selectTownManagedGroupKey(
                    connection, outpost.getId(), group.getId());
            if (lockedGroupId == null || !lockedGroupId.equals(townGroupId)) {
                throw new SQLException("Town-managed group changed during flag update: "
                        + group.getId());
            }
            if (townGroupId != TOWN_DEFAULT_GROUP_ID) {
                requireTownGroup(connection, townId, townGroupId);
            }
            if (isTemplateMode(connection, townId)) {
                requireManageableTemplateFlag(flag);
                replaceValue(connection, "town_group_outpost_override", "dominion_id",
                        outpost.getId(), townGroupId, flag.getFlagName(), value);
                syncOutpostFlags(connection, townId, outpost.getId());
            } else {
                updateMirrorFlag(connection, outpost.getId(), group.getId(), flag, value);
            }
            return null;
        });
        TownGroupSyncHandler.reloadCaches();
    }

    /** Deleting the sparse row restores inheritance immediately. */
    public static void clearOutpostOverride(@NotNull DominionDOO outpost,
                                            @NotNull GroupDTO group,
                                            @NotNull PriFlag flag) throws Exception {
        transaction(connection -> {
            Integer townId = selectOutpostTownId(connection, outpost.getId());
            if (townId == null) {
                throw new SQLException("Outpost does not belong to a town.");
            }
            lockTown(connection, townId);
            requireOutpostOfTown(connection, townId, outpost.getId());
            requireTemplateMode(connection, townId);
            Integer townGroupId = selectTownManagedGroupKey(
                    connection, outpost.getId(), group.getId());
            if (townGroupId == null) {
                throw new SQLException("Group is not managed by the outpost town.");
            }
            if (townGroupId != TOWN_DEFAULT_GROUP_ID) {
                requireTownGroup(connection, townId, townGroupId);
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM town_group_outpost_override "
                            + "WHERE dominion_id = ? AND town_group_id = ? AND flag_name = ?")) {
                delete.setInt(1, outpost.getId());
                delete.setInt(2, townGroupId);
                delete.setString(3, flag.getFlagName());
                delete.executeUpdate();
            }
            syncOutpostFlags(connection, townId, outpost.getId());
            return null;
        });
        TownGroupSyncHandler.reloadCaches();
    }

    public static boolean getTemplateValue(@NotNull TownDOO town, int townGroupId,
                                           @NotNull PriFlag flag) throws SQLException {
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            Boolean value = selectValue(connection, "town_group_flag", "town_id", town.getId(),
                    townGroupId, flag.getFlagName());
            return value != null ? value : flag.getDefaultValue();
        }
    }

    public static @Nullable Boolean getOutpostOverride(@NotNull DominionDOO outpost,
                                                        int townGroupId,
                                                        @NotNull PriFlag flag) throws SQLException {
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            return selectValue(connection, "town_group_outpost_override", "dominion_id",
                    outpost.getId(), townGroupId, flag.getFlagName());
        }
    }

    public static int groupKey(@NotNull GroupDTO group) {
        if (TownManager.TOWN_MEMBER_GROUP_NAME.equals(group.getNamePlain())) {
            return TOWN_DEFAULT_GROUP_ID;
        }
        Integer townGroupId = group.getTownGroupId();
        if (townGroupId == null) {
            throw new IllegalArgumentException("Group is not managed by a town: " + group.getNamePlain());
        }
        return townGroupId;
    }

    /** ADMIN is a legacy dominion-group role flag, not a town role. */
    public static @NotNull List<PriFlag> getManageableTemplateFlags() {
        return Flags.getAllPriFlagsEnable().stream()
                .filter(flag -> !Flags.ADMIN.getFlagName().equals(flag.getFlagName()))
                .toList();
    }

    static void initializeGroupTemplate(Connection connection, int townId,
                                        int townGroupId) throws SQLException {
        ensureDefaultTemplate(connection, townId, TOWN_DEFAULT_GROUP_ID);
        copyTemplateIfMissing(connection, townId, TOWN_DEFAULT_GROUP_ID, townGroupId);
    }

    private static void ensureDefaultTemplate(Connection connection, int townId,
                                              int townGroupId) throws SQLException {
        for (PriFlag flag : Flags.getAllPriFlags()) {
            if (selectValue(connection, "town_group_flag", "town_id", townId,
                    townGroupId, flag.getFlagName()) == null) {
                insertValue(connection, "town_group_flag", "town_id", townId,
                        townGroupId, flag.getFlagName(), flag.getDefaultValue());
            }
        }
    }

    private static void copyTemplateIfMissing(Connection connection, int townId,
                                              int sourceGroupId, int targetGroupId) throws SQLException {
        for (PriFlag flag : Flags.getAllPriFlags()) {
            if (selectValue(connection, "town_group_flag", "town_id", townId,
                    targetGroupId, flag.getFlagName()) != null) continue;
            Boolean value = selectValue(connection, "town_group_flag", "town_id", townId,
                    sourceGroupId, flag.getFlagName());
            insertValue(connection, "town_group_flag", "town_id", townId,
                    targetGroupId, flag.getFlagName(), value != null ? value : flag.getDefaultValue());
        }
    }

    static void syncOutpostFlags(Connection connection, int townId,
                                 int dominionId) throws SQLException {
        List<Integer> groupIds = new ArrayList<>();
        try (PreparedStatement groups = connection.prepareStatement(
                "SELECT DISTINCT town_group_id FROM town_group_flag WHERE town_id = ?")) {
            groups.setInt(1, townId);
            try (ResultSet rows = groups.executeQuery()) {
                while (rows.next()) groupIds.add(rows.getInt(1));
            }
        }
        for (Integer groupId : groupIds) {
            int mirrorId = mirrorId(connection, dominionId, groupId);
            if (mirrorId <= 0) continue;
            for (PriFlag flag : Flags.getAllPriFlags()) {
                Boolean value = selectValue(connection, "town_group_outpost_override",
                        "dominion_id", dominionId, groupId, flag.getFlagName());
                if (value == null) {
                    value = selectValue(connection, "town_group_flag", "town_id",
                            townId, groupId, flag.getFlagName());
                }
                if (value == null) value = flag.getDefaultValue();
                // flag names originate exclusively from the registered
                // schema columns, never from player input.
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE dominion_group SET " + flag.getFlagName() + " = ? WHERE id = ?")) {
                    update.setBoolean(1, value);
                    update.setInt(2, mirrorId);
                    update.executeUpdate();
                }
            }
        }
    }

    private static int mirrorId(Connection connection, int dominionId,
                                int townGroupId) throws SQLException {
        String sql = townGroupId == TOWN_DEFAULT_GROUP_ID
                ? "SELECT id FROM dominion_group WHERE dom_id = ? AND name = ?"
                : "SELECT id FROM dominion_group WHERE dom_id = ? AND town_group_id = ?";
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setInt(1, dominionId);
            if (townGroupId == TOWN_DEFAULT_GROUP_ID) {
                select.setString(2, TownManager.TOWN_MEMBER_GROUP_NAME);
            } else {
                select.setInt(2, townGroupId);
            }
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getInt(1) : -1;
            }
        }
    }

    private static void updateMirrorFlag(Connection connection, int dominionId,
                                         int mirrorId, PriFlag flag,
                                         boolean value) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE dominion_group SET " + flag.getFlagName()
                        + " = ? WHERE id = ? AND dom_id = ?")) {
            update.setBoolean(1, value);
            update.setInt(2, mirrorId);
            update.setInt(3, dominionId);
            if (update.executeUpdate() != 1) {
                throw new SQLException("Group mirror disappeared during flag update: " + mirrorId);
            }
        }
    }

    private static @Nullable Integer selectOutpostTownId(Connection connection,
                                                          int dominionId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT town_id FROM dominion WHERE id = ?")) {
            select.setInt(1, dominionId);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Dominion disappeared during flag update: " + dominionId);
                }
                int townId = row.getInt(1);
                return row.wasNull() ? null : townId;
            }
        }
    }

    private static @Nullable Integer selectTownManagedGroupKey(Connection connection,
                                                                int dominionId,
                                                                int groupId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT name, town_group_id FROM dominion_group WHERE id = ? AND dom_id = ?")) {
            select.setInt(1, groupId);
            select.setInt(2, dominionId);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) return null;
                int townGroupId = row.getInt("town_group_id");
                if (!row.wasNull()) return townGroupId;
                return TownManager.TOWN_MEMBER_GROUP_NAME.equals(row.getString("name"))
                        ? TOWN_DEFAULT_GROUP_ID : null;
            }
        }
    }

    private static void requireTownGroup(Connection connection, int townId,
                                         int townGroupId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM town_group WHERE id = ? AND town_id = ?")) {
            select.setInt(1, townGroupId);
            select.setInt(2, townId);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Town group does not belong to town: " + townGroupId);
                }
            }
        }
    }

    private static void requireManageableTemplateFlag(PriFlag flag) throws SQLException {
        if (Flags.ADMIN.getFlagName().equals(flag.getFlagName())) {
            throw new SQLException("The ADMIN flag is controlled by town roles in template mode.");
        }
    }

    private static void requireUpgradeAllowed(TownDOO town, UUID operatorUuid,
                                              boolean bypass, String mayorUuid,
                                              String permissionMode) throws DominionException {
        if (!bypass && !operatorUuid.toString().equalsIgnoreCase(mayorUuid)) {
            throw new DominionException(Language.townManagerText.templateModeMayorOnly,
                    town.getName());
        }
        if (TownDOO.PermissionMode.TEMPLATE_V1.name().equals(permissionMode)) {
            throw new DominionException(Language.townManagerText.templateModeAlreadyEnabled,
                    town.getName());
        }
    }

    private static void replaceValue(Connection connection, String table, String ownerColumn,
                                     int ownerId, int townGroupId, String flagName,
                                     boolean value) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + ownerColumn
                        + " = ? AND town_group_id = ? AND flag_name = ?")) {
            delete.setInt(1, ownerId);
            delete.setInt(2, townGroupId);
            delete.setString(3, flagName);
            delete.executeUpdate();
        }
        insertValue(connection, table, ownerColumn, ownerId, townGroupId, flagName, value);
    }

    private static void insertValue(Connection connection, String table, String ownerColumn,
                                    int ownerId, int townGroupId, String flagName,
                                    boolean value) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + table + "(" + ownerColumn
                        + ", town_group_id, flag_name, flag_value) VALUES (?, ?, ?, ?)")) {
            insert.setInt(1, ownerId);
            insert.setInt(2, townGroupId);
            insert.setString(3, flagName);
            insert.setBoolean(4, value);
            insert.executeUpdate();
        }
    }

    private static @Nullable Boolean selectValue(Connection connection, String table,
                                                  String ownerColumn, int ownerId,
                                                  int townGroupId, String flagName) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT flag_value FROM " + table + " WHERE " + ownerColumn
                        + " = ? AND town_group_id = ? AND flag_name = ?")) {
            select.setInt(1, ownerId);
            select.setInt(2, townGroupId);
            select.setString(3, flagName);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getBoolean(1) : null;
            }
        }
    }

    /**
     * Portable town-level write lock. MySQL/PostgreSQL lock the selected row;
     * SQLite serializes writers when this no-op UPDATE executes.
     */
    static void lockTown(Connection connection, int townId) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(
                "UPDATE town SET permission_mode = permission_mode WHERE id = ?")) {
            lock.setInt(1, townId);
            lock.executeUpdate();
        }
        try (PreparedStatement verify = connection.prepareStatement(
                "SELECT 1 FROM town WHERE id = ?")) {
            verify.setInt(1, townId);
            try (ResultSet row = verify.executeQuery()) {
                if (!row.next()) throw new SQLException("Town does not exist: " + townId);
            }
        }
    }

    static boolean isTemplateMode(Connection connection, int townId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT permission_mode FROM town WHERE id = ?")) {
            select.setInt(1, townId);
            try (ResultSet row = select.executeQuery()) {
                return row.next() && TownDOO.PermissionMode.TEMPLATE_V1.name().equals(row.getString(1));
            }
        }
    }

    private static void requireTemplateMode(Connection connection, int townId) throws SQLException {
        if (!isTemplateMode(connection, townId)) {
            throw new SQLException("Town has not upgraded to template permission mode: " + townId);
        }
    }

    static void requireOutpostOfTown(Connection connection, int townId,
                                     int dominionId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM dominion WHERE id = ? AND town_id = ?")) {
            select.setInt(1, dominionId);
            select.setInt(2, townId);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) throw new SQLException("Outpost does not belong to town.");
            }
        }
    }

    private TownPermissionManager() {
    }
}
