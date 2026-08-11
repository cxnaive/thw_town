package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.api.dtos.GroupDTO;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.api.dtos.flag.PriFlag;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.cache.server.ServerCache;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private record MirrorRef(int serverId, int groupId) {
    }

    private record MirrorRow(int dominionId, int serverId, int groupId,
                             int townGroupId) {
    }

    private record OverrideKey(int dominionId, int townGroupId,
                               String flagName) {
    }

    private record FlagMirror(int serverId, int groupId, boolean currentValue,
                              @Nullable Boolean overrideValue) {
    }

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
            syncTownFlags(connection, town.getId());
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

            int outposts;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT COUNT(*) FROM dominion WHERE town_id = ?")) {
                select.setInt(1, town.getId());
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    outposts = row.getInt(1);
                }
            }
            syncTownFlags(connection, town.getId());

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
        List<MirrorRef> mirrors = transaction(connection -> {
            lockTown(connection, town.getId());
            requireOutpostOfTown(connection, town.getId(), outpost.getId());
            if (!isTemplateMode(connection, town.getId())) return List.of();
            return syncFlags(connection, town.getId(), outpost.getId());
        });
        reloadGroupCaches(mirrors);
    }

    public static void setTemplateFlag(@NotNull TownDOO town, int townGroupId,
                                       @NotNull PriFlag flag, boolean value) throws Exception {
        List<MirrorRef> mirrors = transaction(connection -> {
            lockTown(connection, town.getId());
            requireTemplateMode(connection, town.getId());
            requireManageableTemplateFlag(flag);
            if (townGroupId != TOWN_DEFAULT_GROUP_ID) {
                requireTownGroup(connection, town.getId(), townGroupId);
            }
            Boolean previous = selectValue(connection, "town_group_flag", "town_id",
                    town.getId(), townGroupId, flag.getFlagName());
            if (previous != null && previous == value) return List.of();
            replaceValue(connection, "town_group_flag", "town_id", town.getId(),
                    townGroupId, flag.getFlagName(), value);
            return syncTownFlag(connection, town.getId(), townGroupId, flag, value);
        });
        reloadGroupCaches(mirrors);
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
                updateMirrorFlag(connection, outpost.getId(), group.getId(), flag, value);
            } else {
                updateMirrorFlag(connection, outpost.getId(), group.getId(), flag, value);
            }
            return null;
        });
        reloadGroupCache(outpost.getServerId(), group.getId());
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
            Boolean inherited = selectValue(connection, "town_group_flag", "town_id",
                    townId, townGroupId, flag.getFlagName());
            updateMirrorFlag(connection, outpost.getId(), group.getId(), flag,
                    inherited != null ? inherited : flag.getDefaultValue());
            return null;
        });
        reloadGroupCache(outpost.getServerId(), group.getId());
    }

    public static boolean getTemplateValue(@NotNull TownDOO town, int townGroupId,
                                           @NotNull PriFlag flag) throws SQLException {
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            Boolean value = selectValue(connection, "town_group_flag", "town_id", town.getId(),
                    townGroupId, flag.getFlagName());
            return value != null ? value : flag.getDefaultValue();
        }
    }

    /** Read a complete template in one database round trip. */
    public static @NotNull Map<String, Boolean> getTemplateValues(@NotNull TownDOO town,
                                                                  int townGroupId) throws SQLException {
        Map<String, Boolean> values = new HashMap<>();
        try (Connection connection = DatabaseManager.instance.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT flag_name, flag_value FROM town_group_flag "
                             + "WHERE town_id = ? AND town_group_id = ?")) {
            select.setInt(1, town.getId());
            select.setInt(2, townGroupId);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    values.put(rows.getString("flag_name"), rows.getBoolean("flag_value"));
                }
            }
        }
        for (PriFlag flag : getManageableTemplateFlags()) {
            values.putIfAbsent(flag.getFlagName(), flag.getDefaultValue());
        }
        return values;
    }

    public static @Nullable Boolean getOutpostOverride(@NotNull DominionDOO outpost,
                                                        int townGroupId,
                                                        @NotNull PriFlag flag) throws SQLException {
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            return selectValue(connection, "town_group_outpost_override", "dominion_id",
                    outpost.getId(), townGroupId, flag.getFlagName());
        }
    }

    /** Read all sparse overrides for one outpost group in one round trip. */
    public static @NotNull Map<String, Boolean> getOutpostOverrides(
            @NotNull DominionDOO outpost, int townGroupId) throws SQLException {
        Map<String, Boolean> values = new HashMap<>();
        try (Connection connection = DatabaseManager.instance.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT flag_name, flag_value FROM town_group_outpost_override "
                             + "WHERE dominion_id = ? AND town_group_id = ?")) {
            select.setInt(1, outpost.getId());
            select.setInt(2, townGroupId);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    values.put(rows.getString("flag_name"), rows.getBoolean("flag_value"));
                }
            }
        }
        return values;
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
        Map<String, Boolean> existing = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT flag_name, flag_value FROM town_group_flag "
                        + "WHERE town_id = ? AND town_group_id = ?")) {
            select.setInt(1, townId);
            select.setInt(2, townGroupId);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    existing.put(rows.getString("flag_name"), rows.getBoolean("flag_value"));
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO town_group_flag(town_id, town_group_id, flag_name, flag_value) "
                        + "VALUES (?, ?, ?, ?)")) {
            boolean hasInserts = false;
            for (PriFlag flag : Flags.getAllPriFlags()) {
                if (existing.containsKey(flag.getFlagName())) continue;
                insert.setInt(1, townId);
                insert.setInt(2, townGroupId);
                insert.setString(3, flag.getFlagName());
                insert.setBoolean(4, flag.getDefaultValue());
                insert.addBatch();
                hasInserts = true;
            }
            if (hasInserts) insert.executeBatch();
        }
    }

    private static void copyTemplateIfMissing(Connection connection, int townId,
                                              int sourceGroupId, int targetGroupId) throws SQLException {
        Map<String, Boolean> source = new HashMap<>();
        Map<String, Boolean> target = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT town_group_id, flag_name, flag_value FROM town_group_flag "
                        + "WHERE town_id = ? AND town_group_id IN (?, ?)")) {
            select.setInt(1, townId);
            select.setInt(2, sourceGroupId);
            select.setInt(3, targetGroupId);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    Map<String, Boolean> destination = rows.getInt("town_group_id") == sourceGroupId
                            ? source : target;
                    destination.put(rows.getString("flag_name"), rows.getBoolean("flag_value"));
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO town_group_flag(town_id, town_group_id, flag_name, flag_value) "
                        + "VALUES (?, ?, ?, ?)")) {
            boolean hasInserts = false;
            for (PriFlag flag : Flags.getAllPriFlags()) {
                if (target.containsKey(flag.getFlagName())) continue;
                insert.setInt(1, townId);
                insert.setInt(2, targetGroupId);
                insert.setString(3, flag.getFlagName());
                insert.setBoolean(4, source.getOrDefault(
                        flag.getFlagName(), flag.getDefaultValue()));
                insert.addBatch();
                hasInserts = true;
            }
            if (hasInserts) insert.executeBatch();
        }
    }

    static void syncOutpostFlags(Connection connection, int townId,
                                 int dominionId) throws SQLException {
        syncFlags(connection, townId, dominionId);
    }

    static void syncTownFlags(Connection connection, int townId) throws SQLException {
        syncFlags(connection, townId, null);
    }

    /**
     * Rebuild complete effective mirror rows with three reads and one JDBC
     * batch, instead of selecting template/override and updating once per
     * flag. {@code dominionId == null} synchronizes the entire town.
     */
    private static List<MirrorRef> syncFlags(Connection connection, int townId,
                                             @Nullable Integer dominionId) throws SQLException {
        Map<Integer, Map<String, Boolean>> templates = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT town_group_id, flag_name, flag_value FROM town_group_flag WHERE town_id = ?")) {
            select.setInt(1, townId);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    templates.computeIfAbsent(rows.getInt("town_group_id"), ignored -> new HashMap<>())
                            .put(rows.getString("flag_name"), rows.getBoolean("flag_value"));
                }
            }
        }

        Map<OverrideKey, Boolean> overrides = new HashMap<>();
        String overrideSql = "SELECT o.dominion_id, o.town_group_id, o.flag_name, o.flag_value "
                + "FROM town_group_outpost_override o JOIN dominion d ON d.id = o.dominion_id "
                + "WHERE d.town_id = ?" + (dominionId == null ? "" : " AND o.dominion_id = ?");
        try (PreparedStatement select = connection.prepareStatement(overrideSql)) {
            select.setInt(1, townId);
            if (dominionId != null) select.setInt(2, dominionId);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    overrides.put(new OverrideKey(rows.getInt("dominion_id"),
                                    rows.getInt("town_group_id"), rows.getString("flag_name")),
                            rows.getBoolean("flag_value"));
                }
            }
        }

        List<MirrorRow> mirrors = new ArrayList<>();
        String mirrorSql = "SELECT d.id AS dominion_id, d.server_id, dg.id AS group_id, "
                + "dg.name, dg.town_group_id FROM dominion d "
                + "JOIN dominion_group dg ON dg.dom_id = d.id WHERE d.town_id = ?"
                + (dominionId == null ? "" : " AND d.id = ?")
                + " AND (dg.town_group_id IS NOT NULL OR dg.name = ?)";
        try (PreparedStatement select = connection.prepareStatement(mirrorSql)) {
            int index = 1;
            select.setInt(index++, townId);
            if (dominionId != null) select.setInt(index++, dominionId);
            select.setString(index, TownManager.TOWN_MEMBER_GROUP_NAME);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    int townGroupId = rows.getInt("town_group_id");
                    if (rows.wasNull()) {
                        if (!TownManager.TOWN_MEMBER_GROUP_NAME.equals(rows.getString("name"))) continue;
                        townGroupId = TOWN_DEFAULT_GROUP_ID;
                    }
                    if (!templates.containsKey(townGroupId)) continue;
                    mirrors.add(new MirrorRow(rows.getInt("dominion_id"),
                            rows.getInt("server_id"), rows.getInt("group_id"), townGroupId));
                }
            }
        }

        if (mirrors.isEmpty()) return List.of();
        List<PriFlag> flags = Flags.getAllPriFlags();
        StringBuilder updateSql = new StringBuilder("UPDATE dominion_group SET ");
        for (PriFlag flag : flags) {
            if (updateSql.charAt(updateSql.length() - 1) != ' ') updateSql.append(", ");
            updateSql.append(flag.getFlagName()).append(" = ?");
        }
        updateSql.append(" WHERE id = ?");
        try (PreparedStatement update = connection.prepareStatement(updateSql.toString())) {
            for (MirrorRow mirror : mirrors) {
                Map<String, Boolean> template = templates.get(mirror.townGroupId());
                int index = 1;
                for (PriFlag flag : flags) {
                    Boolean value = overrides.get(new OverrideKey(mirror.dominionId(),
                            mirror.townGroupId(), flag.getFlagName()));
                    if (value == null) value = template.get(flag.getFlagName());
                    update.setBoolean(index++, value != null ? value : flag.getDefaultValue());
                }
                update.setInt(index, mirror.groupId());
                update.addBatch();
            }
            update.executeBatch();
        }
        return mirrors.stream().map(row -> new MirrorRef(row.serverId(), row.groupId())).toList();
    }

    /** Incrementally propagate one changed template flag to all its mirrors. */
    private static List<MirrorRef> syncTownFlag(Connection connection, int townId,
                                                int townGroupId, PriFlag flag,
                                                boolean templateValue) throws SQLException {
        String groupPredicate = townGroupId == TOWN_DEFAULT_GROUP_ID
                ? "dg.town_group_id IS NULL AND dg.name = ?"
                : "dg.town_group_id = ?";
        String sql = "SELECT d.server_id, dg.id AS group_id, dg." + flag.getFlagName()
                + " AS current_value, o.flag_value AS override_value FROM dominion d "
                + "JOIN dominion_group dg ON dg.dom_id = d.id "
                + "LEFT JOIN town_group_outpost_override o ON o.dominion_id = d.id "
                + "AND o.town_group_id = ? AND o.flag_name = ? "
                + "WHERE d.town_id = ? AND " + groupPredicate;
        List<FlagMirror> mirrors = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setInt(1, townGroupId);
            select.setString(2, flag.getFlagName());
            select.setInt(3, townId);
            if (townGroupId == TOWN_DEFAULT_GROUP_ID) {
                select.setString(4, TownManager.TOWN_MEMBER_GROUP_NAME);
            } else {
                select.setInt(4, townGroupId);
            }
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    boolean override = rows.getBoolean("override_value");
                    Boolean overrideValue = rows.wasNull() ? null : override;
                    mirrors.add(new FlagMirror(rows.getInt("server_id"), rows.getInt("group_id"),
                            rows.getBoolean("current_value"), overrideValue));
                }
            }
        }

        List<MirrorRef> toReload = new ArrayList<>();
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE dominion_group SET " + flag.getFlagName() + " = ? WHERE id = ?")) {
            boolean hasUpdates = false;
            for (FlagMirror mirror : mirrors) {
                boolean effective = mirror.overrideValue() != null
                        ? mirror.overrideValue() : templateValue;
                if (mirror.currentValue() != effective) {
                    toReload.add(new MirrorRef(mirror.serverId(), mirror.groupId()));
                }
                if (mirror.currentValue() == effective) continue;
                update.setBoolean(1, effective);
                update.setInt(2, mirror.groupId());
                update.addBatch();
                hasUpdates = true;
            }
            if (hasUpdates) update.executeBatch();
        }
        return toReload;
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

    private static void reloadGroupCache(int serverId, int groupId) {
        ServerCache cache = CacheManager.instance.getCache(serverId);
        if (cache != null) cache.getGroupCache().reloadNow(groupId);
    }

    private static void reloadGroupCaches(List<MirrorRef> mirrors) {
        // Fetch every changed group for a server in one query instead of one
        // cache read per outpost. LinkedHashSet also deduplicates defensively.
        Map<Integer, Set<Integer>> byServer = new HashMap<>();
        for (MirrorRef mirror : mirrors) {
            byServer.computeIfAbsent(mirror.serverId(), ignored -> new LinkedHashSet<>())
                    .add(mirror.groupId());
        }
        for (Map.Entry<Integer, Set<Integer>> entry : byServer.entrySet()) {
            ServerCache cache = CacheManager.instance.getCache(entry.getKey());
            if (cache != null) cache.getGroupCache().reloadNow(entry.getValue());
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
