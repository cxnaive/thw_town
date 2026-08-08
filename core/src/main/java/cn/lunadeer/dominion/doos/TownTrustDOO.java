package cn.lunadeer.dominion.doos;

import cn.lunadeer.dominion.utils.databse.FIelds.Field;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldBoolean;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldInteger;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldString;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldTimestamp;
import cn.lunadeer.dominion.utils.databse.syntax.Delete;
import cn.lunadeer.dominion.utils.databse.syntax.Insert;
import cn.lunadeer.dominion.utils.databse.syntax.Select;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Independent trust grant: the trusted player need not join the town, may be
 * trusted by any number of towns, and never counts towards max-memberships.
 * scope TOWN applies to every outpost of the town; scope OUTPOST applies to
 * a single dominion (scope_id = dominion.id).
 */
public class TownTrustDOO {

    public enum TrustScope {
        TOWN,
        OUTPOST
    }

    private final FieldInteger townId = new FieldInteger("town_id");
    private final FieldString playerUuid = new FieldString("player_uuid");
    private final FieldString scopeType = new FieldString("scope_type", TrustScope.TOWN.name());
    private final FieldInteger scopeId = new FieldInteger("scope_id", -1);
    private final FieldBoolean build = new FieldBoolean("build", false);
    private final FieldBoolean destroy = new FieldBoolean("destroy", false);
    private final FieldBoolean switchFlag = new FieldBoolean("switch", false);
    private final FieldBoolean itemUse = new FieldBoolean("item_use", false);
    private final FieldString createdBy = new FieldString("created_by");
    private final FieldTimestamp createdAt = new FieldTimestamp("created_at");

    private static Field<?>[] fields() {
        return new Field<?>[]{
                new FieldInteger("town_id"),
                new FieldString("player_uuid"),
                new FieldString("scope_type"),
                new FieldInteger("scope_id"),
                new FieldBoolean("build"),
                new FieldBoolean("destroy"),
                new FieldBoolean("switch"),
                new FieldBoolean("item_use"),
                new FieldString("created_by"),
                new FieldTimestamp("created_at"),
        };
    }

    private static TownTrustDOO parse(Map<String, Field<?>> map) {
        return new TownTrustDOO(
                (Integer) map.get("town_id").getValue(),
                UUID.fromString((String) map.get("player_uuid").getValue()),
                (String) map.get("scope_type").getValue(),
                (Integer) map.get("scope_id").getValue(),
                bool(map.get("build")),
                bool(map.get("destroy")),
                bool(map.get("switch")),
                bool(map.get("item_use")),
                (String) map.get("created_by").getValue(),
                (Timestamp) map.get("created_at").getValue()
        );
    }

    private static boolean bool(Field<?> f) {
        Object v = f.getValue();
        return v != null && (Boolean) v;
    }

    public static @NotNull TownTrustDOO insert(@NotNull Integer townId, @NotNull UUID player,
                                               @NotNull TrustScope scope, @NotNull Integer scopeId,
                                               boolean build, boolean destroy, boolean switchFlag, boolean itemUse,
                                               @NotNull UUID createdBy) throws SQLException {
        TownTrustDOO trust = new TownTrustDOO(townId, player, scope.name(), scopeId,
                build, destroy, switchFlag, itemUse, createdBy.toString(), null);
        Map<String, Field<?>> res = Insert.insert()
                .into("town_trust")
                .values(trust.townId, trust.playerUuid, trust.scopeType, trust.scopeId,
                        trust.build, trust.destroy, trust.switchFlag, trust.itemUse, trust.createdBy)
                .returning(fields())
                .execute();
        if (res.isEmpty()) {
            throw new SQLException("Failed to insert town trust.");
        }
        return parse(res);
    }

    public static @NotNull List<TownTrustDOO> selectByTown(@NotNull Integer townId) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("town_trust")
                .where("town_id = ?", townId)
                .execute();
        return res.stream().map(TownTrustDOO::parse).toList();
    }

    public static @NotNull List<TownTrustDOO> selectByPlayer(@NotNull UUID player) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("town_trust")
                .where("player_uuid = ?", player.toString())
                .execute();
        return res.stream().map(TownTrustDOO::parse).toList();
    }

    public static @NotNull List<TownTrustDOO> selectAll() throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("town_trust")
                .execute();
        return res.stream().map(TownTrustDOO::parse).toList();
    }

    public static void delete(@NotNull Integer townId, @NotNull UUID player,
                              @NotNull TrustScope scope, @NotNull Integer scopeId) throws SQLException {
        Delete.delete().from("town_trust")
                .where("town_id = ? AND player_uuid = ? AND scope_type = ? AND scope_id = ?",
                        townId, player.toString(), scope.name(), scopeId)
                .execute();
    }

    public static void deleteByTown(@NotNull Integer townId) throws SQLException {
        Delete.delete().from("town_trust").where("town_id = ?", townId).execute();
    }

    /**
     * Delete every OUTPOST-scoped grant pointing at the given dominion
     * (any town). Used on detach/release so stale grants cannot silently
     * revive if the dominion later re-attaches to a town.
     */
    public static void deleteByOutpost(@NotNull Integer dominionId) throws SQLException {
        Delete.delete().from("town_trust")
                .where("scope_type = ? AND scope_id = ?", TrustScope.OUTPOST.name(), dominionId)
                .execute();
    }

    public @NotNull Integer getTownId() {
        return townId.getValue();
    }

    public @NotNull UUID getPlayerUuid() {
        return UUID.fromString(playerUuid.getValue());
    }

    public @NotNull TrustScope getScopeType() {
        String v = scopeType.getValue();
        return v == null ? TrustScope.TOWN : TrustScope.valueOf(v);
    }

    public @NotNull Integer getScopeId() {
        return scopeId.getValue();
    }

    public boolean isBuild() {
        Boolean v = build.getValue();
        return v != null && v;
    }

    public boolean isDestroy() {
        Boolean v = destroy.getValue();
        return v != null && v;
    }

    public boolean isSwitch() {
        Boolean v = switchFlag.getValue();
        return v != null && v;
    }

    public boolean isItemUse() {
        Boolean v = itemUse.getValue();
        return v != null && v;
    }

    public @Nullable UUID getCreatedBy() {
        String v = createdBy.getValue();
        return v == null ? null : UUID.fromString(v);
    }

    public @Nullable Timestamp getCreatedAt() {
        return createdAt.getValue();
    }

    private TownTrustDOO(Integer townId, UUID playerUuid, String scopeType, Integer scopeId,
                         boolean build, boolean destroy, boolean switchFlag, boolean itemUse,
                         String createdBy, Timestamp createdAt) {
        this.townId.setValue(townId);
        this.playerUuid.setValue(playerUuid.toString());
        this.scopeType.setValue(scopeType);
        this.scopeId.setValue(scopeId);
        this.build.setValue(build);
        this.destroy.setValue(destroy);
        this.switchFlag.setValue(switchFlag);
        this.itemUse.setValue(itemUse);
        this.createdBy.setValue(createdBy);
        this.createdAt.setValue(createdAt);
    }
}
