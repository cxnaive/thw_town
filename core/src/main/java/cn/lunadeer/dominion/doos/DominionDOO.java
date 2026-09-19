package cn.lunadeer.dominion.doos;

import cn.lunadeer.dominion.Dominion;
import cn.lunadeer.dominion.api.dtos.*;
import cn.lunadeer.dominion.api.dtos.flag.EnvFlag;
import cn.lunadeer.dominion.api.dtos.flag.Flag;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.api.dtos.flag.PriFlag;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.utils.databse.FIelds.Field;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldBoolean;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldInteger;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldLong;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldString;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldTimestamp;
import cn.lunadeer.dominion.utils.databse.syntax.Delete;
import cn.lunadeer.dominion.utils.databse.syntax.Insert;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import cn.lunadeer.dominion.utils.databse.syntax.Select;
import cn.lunadeer.dominion.utils.databse.syntax.Update;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class DominionDOO implements DominionDTO {

    private DominionCuboid cuboid;
    private final FieldInteger id = new FieldInteger("id");
    private final FieldString owner = new FieldString("owner");
    private final FieldString name = new FieldString("name");
    private final FieldInteger parentDomId = new FieldInteger("parent_dom_id", -1);
    private final FieldString joinMessage = new FieldString("join_message", "");
    private final FieldString leaveMessage = new FieldString("leave_message", "");
    private final Map<EnvFlag, Boolean> envFlags = new HashMap<>();
    private final Map<PriFlag, Boolean> preFlags = new HashMap<>();
    private final FieldString tp_location = new FieldString("tp_location", "default");
    private final FieldString color = new FieldString("color", "#00BFFF");
    private final FieldString world_uid = new FieldString("world_uid");
    private final FieldInteger serverId = new FieldInteger("server_id");
    // Town era: nullable attachment columns (NULL = townless outpost)
    private final FieldInteger townId = new FieldInteger("town_id");
    private final FieldString claimKind = new FieldString("claim_kind", "NORMAL");
    private final FieldString outpostAdminUuid = new FieldString("outpost_admin_uuid");
    private final FieldString attachedBy = new FieldString("attached_by");
    private final FieldTimestamp attachedAt = new FieldTimestamp("attached_at");
    // Gross tier value (in cents) actually paid by the town bank for this
    // outpost. Historical/attached land starts at zero, preventing refunds
    // for value the town never bought.
    private final FieldLong townPaidValue = new FieldLong("town_paid_value", 0L);

    // Cache for the parsed UUID to avoid repeated string parsing
    private UUID cachedWorldUid = null;

    private static Field<?>[] fields() {
        Field<?>[] fields = new Field<?>[6 + 6 + Flags.getAllEnvFlagsEnable().size() + Flags.getAllPriFlagsEnable().size() + 4 + 6];
        fields[0] = rootDominion().cuboid.x1Field();
        fields[1] = rootDominion().cuboid.y1Field();
        fields[2] = rootDominion().cuboid.z1Field();
        fields[3] = rootDominion().cuboid.x2Field();
        fields[4] = rootDominion().cuboid.y2Field();
        fields[5] = rootDominion().cuboid.z2Field();
        fields[6] = rootDominion().id;
        fields[7] = rootDominion().owner;
        fields[8] = rootDominion().name;
        fields[9] = rootDominion().world_uid;
        fields[10] = rootDominion().parentDomId;
        fields[11] = rootDominion().joinMessage;
        fields[12] = rootDominion().leaveMessage;
        int i = 13;
        for (Flag f : Flags.getAllEnvFlagsEnable()) {
            fields[i++] = new FieldBoolean(f.getFlagName(), f.getDefaultValue());
        }
        for (Flag f : Flags.getAllPriFlagsEnable()) {
            fields[i++] = new FieldBoolean(f.getFlagName(), f.getDefaultValue());
        }
        fields[i++] = rootDominion().tp_location;
        fields[i++] = rootDominion().color;
        fields[i++] = rootDominion().serverId;
        fields[i++] = rootDominion().townId;
        fields[i++] = rootDominion().claimKind;
        fields[i++] = rootDominion().outpostAdminUuid;
        fields[i++] = rootDominion().attachedBy;
        fields[i++] = rootDominion().attachedAt;
        fields[i++] = rootDominion().townPaidValue;
        return fields;
    }

    private static DominionDOO parse(Map<String, Field<?>> map) {
        DominionCuboid cuboid = new DominionCuboid(
                (int) map.get("x1").getValue(),
                (int) map.get("y1").getValue(),
                (int) map.get("z1").getValue(),
                (int) map.get("x2").getValue(),
                (int) map.get("y2").getValue(),
                (int) map.get("z2").getValue()
        );
        Map<EnvFlag, Boolean> envFlags = new HashMap<>();
        for (EnvFlag f : Flags.getAllEnvFlagsEnable()) {
            envFlags.put(f, (Boolean) map.get(f.getFlagName()).getValue());
        }
        Map<PriFlag, Boolean> preFlags = new HashMap<>();
        for (PriFlag f : Flags.getAllPriFlagsEnable()) {
            preFlags.put(f, (Boolean) map.get(f.getFlagName()).getValue());
        }
        return new DominionDOO(
                (Integer) map.get("id").getValue(),
                UUID.fromString((String) map.get("owner").getValue()),
                (String) map.get("name").getValue(),
                UUID.fromString((String) map.get("world_uid").getValue()),
                cuboid,
                (Integer) map.get("parent_dom_id").getValue(),
                (String) map.get("join_message").getValue(),
                (String) map.get("leave_message").getValue(),
                envFlags,
                preFlags,
                (String) map.get("tp_location").getValue(),
                (String) map.get("color").getValue(),
                (Integer) map.get("server_id").getValue(),
                (Integer) map.get("town_id").getValue(),
                (String) map.get("claim_kind").getValue(),
                (String) map.get("outpost_admin_uuid").getValue(),
                (String) map.get("attached_by").getValue(),
                (java.sql.Timestamp) map.get("attached_at").getValue(),
                (Long) map.get("town_paid_value").getValue()
        );
    }

    public static List<DominionDOO> selectAll(Integer serverId) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("dominion")
                .where("server_id = ? AND id >= 0", serverId)
                .execute();
        return res.stream().map(DominionDOO::parse).toList();
    }

    public static DominionDOO rootDominion() {
        return new DominionDOO(-1,
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                "根领地", UUID.fromString("00000000-0000-0000-0000-000000000000"),
                new DominionCuboid(-2147483648, -2147483648, -2147483648, 2147483647, 2147483647, 2147483647),
                -1,
                "null", "null",
                new HashMap<>(), new HashMap<>(),
                "default", "#00BFFF", -1,
                null, "NORMAL", null, null, null, 0L);
    }

    public static @Nullable DominionDOO select(Integer id) throws SQLException {
        if (id == -1) {
            return rootDominion();
        }
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("dominion")
                .where("id = ?", id)
                .execute();
        if (res.isEmpty()) return null;
        return parse(res.get(0));
    }

    public static @Nullable DominionDOO select(String name) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields()).from("dominion").where("name = ?", name).execute();
        if (res.isEmpty()) return null;
        return parse(res.get(0));
    }

    /** Current row on the lifecycle transaction's connection; never opens another connection. */
    public static @Nullable DominionDOO selectForUpdate(Connection connection, int id) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("A land transaction is required.");
        boolean sqlite = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("SQLite");
        // SQLite callers already hold the write lock through their town UPDATE.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM dominion WHERE id = ?" + (sqlite ? "" : " FOR UPDATE"))) {
            statement.setInt(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                List<Map<String, Field<?>>> values = Field.getFromResultSet(fields(), rows);
                return values.isEmpty() ? null : parse(values.get(0));
            }
        }
    }

    public static @NotNull List<DominionDOO> selectByTownId(Integer townId) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("dominion")
                .where("town_id = ?", townId)
                .execute();
        return res.stream().map(DominionDOO::parse).toList();
    }


    public static int countByTownId(Integer townId) throws SQLException {
        try (Connection conn = DatabaseManager.instance.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM dominion WHERE town_id = ?")) {
            ps.setInt(1, townId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Outposts whose appointed admin is this player (plan §2.4) — the
     * reverse lookup the town-aware dominion list needs, since outpost
     * admins are neither owners nor members.
     */
    public static @NotNull List<DominionDOO> selectByOutpostAdmin(@NotNull UUID admin) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("dominion")
                .where("outpost_admin_uuid = ?", admin.toString())
                .execute();
        return res.stream().map(DominionDOO::parse).toList();
    }

    /**
     * Every dominion owned by a player, across ALL backend servers, from
     * the database. Town attachment must not rely on the cross-server
     * cache replicas: a dominion created on another backend seconds ago
     * may be missing from the local replica and would silently skip
     * attachment (plan §3 invariant 3).
     */
    public static @NotNull List<DominionDOO> selectByOwner(@NotNull UUID owner) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("dominion")
                .where("owner = ?", owner.toString())
                .execute();
        return res.stream().map(DominionDOO::parse).toList();
    }

    /**
     * Direct children by parent id — a fresh DB query, unlike the
     * cache-based {@code Others.getSubDominionsRecursive}. Disband uses
     * this so sub-dominions created on other backend servers (or within
     * the cache throttle window) are never silently cascade-deleted
     * without an archive row.
     */
    public static @NotNull List<DominionDOO> selectByParentId(Integer parentId) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("dominion")
                .where("parent_dom_id = ?", parentId)
                .execute();
        return res.stream().map(DominionDOO::parse).toList();
    }

    /**
     * The whole sub-tree below this dominion, deepest first (safe delete
     * order against the parent_dom_id FK cascade). DB-backed, see
     * {@link #selectByParentId}. Cycle-guarded: a hand-crafted or
     * corrupted import can create parent_dom_id loops that would
     * otherwise recurse forever.
     */
    public @NotNull List<DominionDOO> selectSubTreeDeepestFirst() throws SQLException {
        return selectSubTreeDeepestFirst(new java.util.HashSet<>());
    }

    private @NotNull List<DominionDOO> selectSubTreeDeepestFirst(@NotNull java.util.Set<Integer> visited) throws SQLException {
        List<DominionDOO> result = new ArrayList<>();
        for (DominionDOO child : selectByParentId(getId())) {
            if (!visited.add(child.getId())) {
                continue; // cycle in imported data — skip the loop edge
            }
            result.addAll(child.selectSubTreeDeepestFirst(visited));
            result.add(child);
        }
        return result;
    }

    public static @NotNull DominionDOO insert(DominionDOO dominion) throws SQLException {
        Map<String, Field<?>> res = Insert.insert()
                .into("dominion")
                .values(dominion.owner,
                        dominion.name,
                        dominion.world_uid,
                        dominion.cuboid.x1Field(), dominion.cuboid.y1Field(), dominion.cuboid.z1Field(),
                        dominion.cuboid.x2Field(), dominion.cuboid.y2Field(), dominion.cuboid.z2Field(),
                        dominion.parentDomId,
                        dominion.joinMessage, dominion.leaveMessage,
                        dominion.tp_location,
                        dominion.serverId)
                .returning(fields())
                .execute();
        if (res.isEmpty()) {
            throw new SQLException("Failed to insert dominion.");
        }
        DominionDOO inserted = parse(res);
        CacheManager.instance.getCache().getDominionCache().load(inserted.getId());
        return inserted;
    }

    public static void deleteById(Integer dominion) throws SQLException {
        // Keep the new sparse override table clean even on legacy SQLite
        // databases that were created while foreign_keys was disabled.
        Delete.delete()
                .from("town_group_outpost_override")
                .where("dominion_id = ?", dominion)
                .execute();
        Delete.delete()
                .from("dominion")
                .where("id = ?", dominion)
                .execute();
        CacheManager.instance.getCache().getDominionCache().delete(dominion);
    }

    // full constructor
    private DominionDOO(Integer id, UUID owner, String name, UUID world_uid,
                        DominionCuboid cuboid,
                        Integer parentDomId,
                        String joinMessage, String leaveMessage,
                        Map<EnvFlag, Boolean> envFlags,
                        Map<PriFlag, Boolean> preFlags,
                        String tp_location,
                        String color,
                        Integer serverId,
                        Integer townId,
                        String claimKind,
                        String outpostAdminUuid,
                        String attachedBy,
                        java.sql.Timestamp attachedAt,
                        Long townPaidValue) {
        this.id.setValue(id);
        this.owner.setValue(owner.toString());
        this.name.setValue(name);
        this.world_uid.setValue(world_uid.toString());
        this.cuboid = cuboid;
        this.parentDomId.setValue(parentDomId);
        this.joinMessage.setValue(joinMessage);
        this.leaveMessage.setValue(leaveMessage);
        this.envFlags.putAll(envFlags);
        this.preFlags.putAll(preFlags);
        this.tp_location.setValue(tp_location);
        this.color.setValue(color);
        this.serverId.setValue(serverId);
        this.townId.setValue(townId);
        this.claimKind.setValue(claimKind);
        this.outpostAdminUuid.setValue(outpostAdminUuid);
        this.attachedBy.setValue(attachedBy);
        this.attachedAt.setValue(attachedAt);
        this.townPaidValue.setValue(townPaidValue == null ? 0L : townPaidValue);
    }

    // constructor for new dominion
    public DominionDOO(@NotNull UUID owner,
                       @NotNull String name,
                       @NotNull UUID world_uid,
                       @NotNull CuboidDTO cuboid,
                       @NotNull Integer parentDomId) {
        this.owner.setValue(owner.toString());
        this.name.setValue(name);
        this.world_uid.setValue(world_uid.toString());
        this.cuboid = new DominionCuboid(cuboid);
        this.parentDomId.setValue(parentDomId);
        this.joinMessage.setValue(Configuration.pluginMessage.defaultEnterMessage);
        this.leaveMessage.setValue(Configuration.pluginMessage.defaultLeaveMessage);
        this.serverId.setValue(Configuration.multiServer.serverId);
    }

    private static class DominionCuboid extends CuboidDTO {
        public DominionCuboid(int x1, int y1, int z1, int x2, int y2, int z2) {
            super(x1, y1, z1, x2, y2, z2);
        }

        public DominionCuboid(CuboidDTO superObj) {
            super(superObj.x1(), superObj.y1(), superObj.z1(), superObj.x2(), superObj.y2(), superObj.z2());
        }

        public DominionCuboid(ResultSet rs) throws SQLException {
            super(rs.getInt("x1"), rs.getInt("y1"), rs.getInt("z1"),
                    rs.getInt("x2"), rs.getInt("y2"), rs.getInt("z2"));
        }

        public FieldInteger x1Field() {
            return new FieldInteger("x1", x1());
        }

        public FieldInteger y1Field() {
            return new FieldInteger("y1", y1());
        }

        public FieldInteger z1Field() {
            return new FieldInteger("z1", z1());
        }

        public FieldInteger x2Field() {
            return new FieldInteger("x2", x2());
        }

        public FieldInteger y2Field() {
            return new FieldInteger("y2", y2());
        }

        public FieldInteger z2Field() {
            return new FieldInteger("z2", z2());
        }
    }

    // getters and setters
    @Override
    public @NotNull Integer getId() {
        return id.getValue();
    }

    /**
     * 设置领地ID，该方法不会更新数据库，仅用于构造对象
     *
     * @param id 领地ID
     * @return 领地
     */
    public @NotNull DominionDOO setId(Integer id) {
        this.id.setValue(id);
        return this;
    }

    @Override
    public @NotNull UUID getOwner() {
        return UUID.fromString(owner.getValue());
    }

    @Override
    public @NotNull PlayerDTO getOwnerDTO() {
        UUID owner = getOwner();
        return Objects.requireNonNull(CacheManager.instance.getPlayer(owner),
                "Missing player record for owner of dominion " + getId() + " (owner uuid: " + owner + ")");
    }

    @Override
    public @NotNull DominionDOO setOwner(UUID owner) throws SQLException {
        // town era: owner_uuid on a town outpost is the contributor record
        // (restitution basis for disband-return), not a transferable title —
        // third-party plugins must not rewrite it through the DTO. The only
        // internal writers are the provider transfer/give paths, which
        // already reject town outposts before reaching here.
        if (isTownOutpost()) {
            throw new IllegalStateException(
                    "Dominion " + getId() + " (" + getName() + ") is a town outpost; "
                            + "owner is a contributor record and cannot be transferred. "
                            + "Use the town admin release flow instead.");
        }
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE dominion SET owner = ? WHERE id = ? AND town_id IS NULL")) {
                    statement.setString(1, owner.toString());
                    statement.setInt(2, id.getValue());
                    updated = statement.executeUpdate();
                }
                if (updated != 1) {
                    try (PreparedStatement verify = connection.prepareStatement(
                            "SELECT owner, town_id FROM dominion WHERE id = ?")) {
                        verify.setInt(1, id.getValue());
                        try (ResultSet row = verify.executeQuery()) {
                            if (!row.next()) {
                                throw new SQLException("Dominion " + id.getValue() + " no longer exists.");
                            }
                            if (row.getObject("town_id") != null) {
                                throw new IllegalStateException(
                                        "Dominion " + getId() + " (" + getName() + ") became a town outpost "
                                                + "while being transferred; owner was not changed.");
                            }
                            // MySQL may report zero for a matched no-op UPDATE
                            // with useAffectedRows=true. Treat an already-equal
                            // owner as an idempotent success.
                            if (!owner.toString().equals(row.getString("owner"))) {
                                throw new SQLException("Dominion " + id.getValue()
                                        + " owner changed concurrently; retry the transfer.");
                            }
                        }
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
        this.owner.setValue(owner.toString());
        cn.lunadeer.dominion.cache.server.ServerCache serverCache =
                CacheManager.instance.getCache(getServerId());
        if (serverCache != null) serverCache.getDominionCache().load(getId());
        return this;
    }

    @Override
    public @NotNull DominionDOO setOwner(Player owner) throws SQLException {
        return setOwner(owner.getUniqueId());
    }

    @Override
    public @NotNull String getName() {
        return name.getValue();
    }

    // ---------- Town era: attachment state ----------

    /**
     * The town this dominion is attached to, or null for a townless outpost.
     * NULL town_id preserves the pre-town permission chain byte-for-byte.
     */
    public @Nullable Integer getTownId() {
        return townId.getValue();
    }

    public boolean isTownOutpost() {
        return townId.getValue() != null;
    }

    public @NotNull String getClaimKind() {
        String v = claimKind.getValue();
        return v == null ? "NORMAL" : v;
    }

    public @Nullable UUID getOutpostAdminUuid() {
        String v = outpostAdminUuid.getValue();
        return v == null ? null : UUID.fromString(v);
    }

    public @Nullable UUID getAttachedBy() {
        String v = attachedBy.getValue();
        return v == null ? null : UUID.fromString(v);
    }

    public @Nullable java.sql.Timestamp getAttachedAt() {
        return attachedAt.getValue();
    }

    public long getTownPaidValueCents() {
        Long value = townPaidValue.getValue();
        return value == null ? 0L : Math.max(0L, value);
    }

    /**
     * Attach this dominion to a town (outpost conversion). owner_uuid keeps
     * its value as the contributor record; permission resolution switches to
     * the town cascade based on town_id alone.
     */
    public @NotNull DominionDOO attachToTown(@NotNull Integer town, @NotNull UUID operator) throws SQLException {
        this.townId.setValue(town);
        this.attachedBy.setValue(operator.toString());
        this.attachedAt.setValue(new java.sql.Timestamp(System.currentTimeMillis()));
        this.townPaidValue.setValue(0L);
        Update.update("dominion")
                .set(this.townId, this.attachedBy, this.attachedAt, this.townPaidValue)
                .where("id = ?", id.getValue())
                .execute();
        CacheManager.instance.getCache().getDominionCache().load(id.getValue());
        return this;
    }

    /**
     * Detach this dominion from its town (admin release / disband return).
     * Clears the outpost admin as well: the town cascade no longer
     * applies. OUTPOST-scoped trust grants pointing at this dominion are
     * deleted so they cannot silently revive on a later re-attachment.
     */
    public @NotNull DominionDOO detachFromTown() throws SQLException {
        this.townId.setValue(null);
        this.attachedBy.setValue(null);
        this.attachedAt.setValue(null);
        this.outpostAdminUuid.setValue(null);
        this.townPaidValue.setValue(0L);
        Update.update("dominion")
                .set(this.townId, this.attachedBy, this.attachedAt, this.outpostAdminUuid, this.townPaidValue)
                .where("id = ?", id.getValue())
                .execute();
        TownTrustDOO.deleteByOutpost(id.getValue());
        CacheManager.instance.getCache().getDominionCache().load(id.getValue());
        return this;
    }

    /**
     * Re-attach this sub-dominion to the root level (parent_dom_id = -1).
     * Used by return-contributed disband: a contributed sub inside a
     * town-native outpost being dissolved must survive its parent, and the
     * FK cascade makes that impossible without reparenting first.
     */
    public @NotNull DominionDOO reparentToRoot() throws SQLException {
        this.parentDomId.setValue(-1);
        Update.update("dominion")
                .set(this.parentDomId)
                .where("id = ?", id.getValue())
                .execute();
        CacheManager.instance.getCache().getDominionCache().load(id.getValue());
        return this;
    }

    /**
     * Mark a freshly inserted dominion as born into a town (founding/remote
     * outpost): town_id is set with NO attached_by, distinguishing
     * town-native land from contributed legacy land on disband.
     */
    public @NotNull DominionDOO bornIntoTown(@NotNull Integer town) throws SQLException {
        this.townId.setValue(town);
        Update.update("dominion")
                .set(this.townId)
                .where("id = ?", id.getValue())
                .execute();
        CacheManager.instance.getCache().getDominionCache().load(id.getValue());
        return this;
    }

    public @NotNull DominionDOO setOutpostAdmin(@Nullable UUID admin) throws SQLException {
        this.outpostAdminUuid.setValue(admin == null ? null : admin.toString());
        Update.update("dominion")
                .set(this.outpostAdminUuid)
                .where("id = ?", id.getValue())
                .execute();
        CacheManager.instance.getCache().getDominionCache().load(id.getValue());
        return this;
    }

    public @NotNull DominionDOO setClaimKind(@NotNull String kind) throws SQLException {
        this.claimKind.setValue(kind);
        Update.update("dominion")
                .set(this.claimKind)
                .where("id = ?", id.getValue())
                .execute();
        CacheManager.instance.getCache().getDominionCache().load(id.getValue());
        return this;
    }

    public @NotNull DominionDOO setTownPaidValueCents(long cents) throws SQLException {
        if (cents < 0) {
            throw new IllegalArgumentException("town paid value must be non-negative");
        }
        FieldLong nextValue = new FieldLong("town_paid_value", cents);
        int updated = Update.update("dominion")
                .set(nextValue)
                .where("id = ?", id.getValue())
                .execute();
        if (updated != 1) {
            throw new SQLException("Dominion " + id.getValue() + " no longer exists.");
        }
        this.townPaidValue.setValue(cents);
        CacheManager.instance.getCache().getDominionCache().load(id.getValue());
        return this;
    }

    @Override
    public @NotNull DominionDOO setName(String name) throws SQLException {
        String oldName = this.name.getValue();
        this.name.setValue(name);
        Update.update("dominion")
                .set(this.name)
                .where("id = ?", id.getValue())
                .execute();
        CacheManager.instance.getCache().getDominionCache().dominionNameUpdate(oldName, name, getId());
        return this;
    }

    @Override
    public @Nullable World getWorld() {
        return Dominion.instance.getServer().getWorld(getWorldUid());
    }

    @Override
    public @NotNull UUID getWorldUid() {
        // Return cached UUID if available
        if (cachedWorldUid == null) {
            cachedWorldUid = UUID.fromString(world_uid.getValue());
        }
        return cachedWorldUid;
    }

    @Override
    public @NotNull CuboidDTO getCuboid() {
        return cuboid;
    }

    @Override
    public @NotNull DominionDOO setCuboid(@NotNull CuboidDTO cuboid) throws SQLException {
        if (isTownOutpost()) {
            // Direct DTO mutation bypasses town economy. Fail closed on the
            // refund ledger so free land cannot later become a bank refund.
            return setCuboidAndTownPaidValue(this.cuboid, cuboid, getTownPaidValueCents(), 0);
        }
        this.cuboid = new DominionCuboid(cuboid);
        Update.update("dominion")
                .set(this.cuboid.x1Field(),
                        this.cuboid.y1Field(),
                        this.cuboid.z1Field(),
                        this.cuboid.x2Field(),
                        this.cuboid.y2Field(),
                        this.cuboid.z2Field())
                .where("id = ?", id.getValue())
                .execute();
        CacheManager.instance.getCache().getDominionCache().load(getId());
        return this;
    }

    /** Persist the land shape and its refundable town-paid value together. */
    public @NotNull DominionDOO setCuboidAndTownPaidValue(@NotNull CuboidDTO expectedCuboid,
                                                          @NotNull CuboidDTO cuboid,
                                                          long expectedPaidValueCents,
                                                          long paidValueCents) throws SQLException {
        if (expectedPaidValueCents < 0 || paidValueCents < 0) {
            throw new IllegalArgumentException("town paid value must be non-negative");
        }
        DominionCuboid nextCuboid = new DominionCuboid(cuboid);
        FieldLong nextPaidValue = new FieldLong("town_paid_value", paidValueCents);
        int updated = Update.update("dominion")
                .set(nextCuboid.x1Field(), nextCuboid.y1Field(), nextCuboid.z1Field(),
                        nextCuboid.x2Field(), nextCuboid.y2Field(), nextCuboid.z2Field(),
                        nextPaidValue)
                .where("id = ? AND x1 = ? AND y1 = ? AND z1 = ? AND x2 = ? AND y2 = ? AND z2 = ? "
                                + "AND town_paid_value = ?",
                        id.getValue(),
                        expectedCuboid.x1(), expectedCuboid.y1(), expectedCuboid.z1(),
                        expectedCuboid.x2(), expectedCuboid.y2(), expectedCuboid.z2(),
                        expectedPaidValueCents)
                .execute();
        if (updated != 1) {
            throw new SQLException("Dominion " + id.getValue()
                    + " changed concurrently; retry the resize.");
        }
        this.cuboid = nextCuboid;
        this.townPaidValue.setValue(paidValueCents);
        CacheManager.instance.getCache().getDominionCache().load(getId());
        return this;
    }

    @Override
    public @NotNull Integer getParentDomId() {
        return parentDomId.getValue();
    }

    @Override
    public @NotNull String getJoinMessage() {
        return joinMessage.getValue();
    }

    @Override
    public @NotNull DominionDOO setJoinMessage(String joinMessage) throws SQLException {
        this.joinMessage.setValue(joinMessage);
        Update.update("dominion")
                .set(this.joinMessage)
                .where("id = ?", id.getValue())
                .execute();
        return this;
    }

    @Override
    public @NotNull String getLeaveMessage() {
        return leaveMessage.getValue();
    }

    @Override
    public @NotNull DominionDOO setLeaveMessage(String leaveMessage) throws SQLException {
        this.leaveMessage.setValue(leaveMessage);
        Update.update("dominion")
                .set(this.leaveMessage)
                .where("id = ?", id.getValue())
                .execute();
        return this;
    }

    @Override
    public @NotNull Map<EnvFlag, Boolean> getEnvironmentFlagValue() {
        return envFlags;
    }

    /**
     * 获取领地某个环境配置的值
     *
     * @param flag 权限
     * @return 权限值
     */
    @Override
    public boolean getEnvFlagValue(@NotNull EnvFlag flag) {
        return envFlags.getOrDefault(flag, false);
    }

    @Override
    public @NotNull Map<PriFlag, Boolean> getGuestPrivilegeFlagValue() {
        return preFlags;
    }

    /**
     * 获取领地某个访客权限的值
     *
     * @param flag 权限
     * @return 权限值
     */
    @Override
    public boolean getGuestFlagValue(@NotNull PriFlag flag) {
        if (preFlags.equals(Flags.ADMIN)) { // guest's admin flag is always false
            return false;
        }
        return preFlags.getOrDefault(flag, false);
    }

    @Override
    public @NotNull DominionDOO setEnvFlagValue(@NotNull EnvFlag flag, @NotNull Boolean value) throws SQLException {
        envFlags.put(flag, value);
        FieldBoolean flagField = new FieldBoolean(flag.getFlagName(), value);
        Update.update("dominion")
                .set(flagField)
                .where("id = ?", id.getValue())
                .execute();
        return this;
    }

    @Override
    public @NotNull DominionDOO setGuestFlagValue(@NotNull PriFlag flag, @NotNull Boolean value) throws SQLException {
        preFlags.put(flag, value);
        FieldBoolean flagField = new FieldBoolean(flag.getFlagName(), value);
        Update.update("dominion")
                .set(flagField)
                .where("id = ?", id.getValue())
                .execute();
        return this;
    }


    /** Stored coordinates, including the "default" marker; never queries terrain. */
    public @NotNull String getStoredTpLocation() {
        return tp_location.getValue();
    }

    /** Coordinates for display/events. An unset point is the geometric center, not a resolved landing. */
    @Override
    public @NotNull Location getTpLocation() {
        if (Objects.equals(tp_location.getValue(), "default")) {
            World w = getWorld();
            double centerX = ((double) cuboid.x1() + cuboid.x2()) / 2;
            double centerZ = ((double) cuboid.z1() + cuboid.z2()) / 2;
            double centerY = ((double) cuboid.y1() + cuboid.y2()) / 2;
            return new Location(w, centerX, centerY, centerZ);
        } else {
            // 0:0:0
            String[] loc = tp_location.getValue().split(":");
            World w = getWorld();
            if (loc.length == 3 && w != null) {
                return new Location(w, Integer.parseInt(loc[0]), Integer.parseInt(loc[1]), Integer.parseInt(loc[2]));
            } else if (loc.length == 5 && w != null) {
                return new Location(w, Integer.parseInt(loc[0]), Integer.parseInt(loc[1]), Integer.parseInt(loc[2]),
                        Float.parseFloat(loc[3]), Float.parseFloat(loc[4]));
            } else {
                return new Location(getWorld(),
                        (double) (cuboid.x1() + cuboid.x2()) / 2,
                        (double) (cuboid.y1() + cuboid.y2()) / 2,
                        (double) (cuboid.z1() + cuboid.z2()) / 2);
            }
        }
    }

    @Override
    public @NotNull DominionDOO setTpLocation(Location loc) throws SQLException {
        this.tp_location.setValue(loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ() + ":" + loc.getYaw() + ":" + loc.getPitch());
        Update.update("dominion")
                .set(this.tp_location)
                .where("id = ?", id.getValue())
                .execute();
        return this;
    }

    @Override
    public boolean isDefaultTpLocation() {
        return Objects.equals(tp_location.getValue(), "default");
    }

    public @NotNull DominionDOO setColor(@NotNull Color color) throws SQLException {
        this.color.setValue(String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue()));
        Update.update("dominion")
                .set(this.color)
                .where("id = ?", id.getValue())
                .execute();
        return this;
    }

    @Override
    public List<GroupDTO> getGroups() {
        return Objects.requireNonNull(CacheManager.instance.getCache(getServerId())).getGroupCache().getDominionGroups(this);
    }

    @Override
    public List<MemberDTO> getMembers() {
        return Objects.requireNonNull(CacheManager.instance.getCache(getServerId())).getMemberCache().getDominionMembers(this);
    }

    @Override
    public Integer getServerId() {
        return serverId.getValue();
    }

    @Override
    public int getColorR() {
        return Integer.valueOf(getColor().substring(1, 3), 16);
    }

    @Override
    public int getColorG() {
        return Integer.valueOf(getColor().substring(3, 5), 16);
    }

    @Override
    public int getColorB() {
        return Integer.valueOf(getColor().substring(5, 7), 16);
    }

    @Override
    public @NotNull String getColor() {
        return color.getValue();
    }

    @Override
    public int getColorHex() {
        return (getColorR() << 16) + (getColorG() << 8) + getColorB();
    }

    /**
     * Delete dominion by player UUID.
     * <p>
     * THIS SHOULD ONLY BE USED TO CLEAR LEGACY DATA.
     *
     * @param playerUUID the UUID of the player to delete
     * @throws SQLException if a database access error occurs
     */
    public static void deleteByPlayerUuid(UUID playerUUID) throws SQLException {
        Delete.delete().from("dominion").where("owner = ?", playerUUID.toString()).execute();
    }
}
