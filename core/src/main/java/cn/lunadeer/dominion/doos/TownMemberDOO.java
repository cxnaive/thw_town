package cn.lunadeer.dominion.doos;

import cn.lunadeer.dominion.utils.databse.FIelds.Field;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldInteger;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldString;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldTimestamp;
import cn.lunadeer.dominion.utils.databse.syntax.Delete;
import cn.lunadeer.dominion.utils.databse.syntax.Insert;
import cn.lunadeer.dominion.utils.databse.syntax.Select;
import cn.lunadeer.dominion.utils.databse.syntax.Update;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Formal membership of a player in a town. Primary key is the composite
 * (town_id, player_uuid) enforced by idx_town_member_pk; a player may
 * legally belong to several towns (max-memberships gates NEW memberships
 * only, and is counted as COUNT(DISTINCT town_id)).
 */
public class TownMemberDOO {

    public enum TownRole {
        MAYOR,
        ASSISTANT,
        RESIDENT
    }

    public enum MemberSource {
        FOUNDER,
        JOINED,
        MERGED
    }

    private final FieldInteger townId = new FieldInteger("town_id");
    private final FieldString playerUuid = new FieldString("player_uuid");
    private final FieldString role = new FieldString("role", TownRole.RESIDENT.name());
    private final FieldString source = new FieldString("source", MemberSource.JOINED.name());
    private final FieldTimestamp joinedAt = new FieldTimestamp("joined_at");

    private static Field<?>[] fields() {
        return new Field<?>[]{
                new FieldInteger("town_id"),
                new FieldString("player_uuid"),
                new FieldString("role"),
                new FieldString("source"),
                new FieldTimestamp("joined_at"),
        };
    }

    private static TownMemberDOO parse(Map<String, Field<?>> map) {
        return new TownMemberDOO(
                (Integer) map.get("town_id").getValue(),
                UUID.fromString((String) map.get("player_uuid").getValue()),
                (String) map.get("role").getValue(),
                (String) map.get("source").getValue(),
                (Timestamp) map.get("joined_at").getValue()
        );
    }

    public static @NotNull TownMemberDOO insert(@NotNull Integer townId, @NotNull UUID player,
                                                @NotNull TownRole role, @NotNull MemberSource source) throws SQLException {
        TownMemberDOO member = new TownMemberDOO(townId, player, role.name(), source.name(), null);
        // no .returning() here: town_member has no id column, and the MySQL
        // returning-emulation selects by `id = LAST_INSERT_ID()` — broken
        // for composite-PK tables. Re-select by the composite key instead.
        Insert.insert()
                .into("town_member")
                .values(member.townId, member.playerUuid, member.role, member.source)
                .execute();
        TownMemberDOO inserted = select(townId, player);
        if (inserted == null) {
            throw new SQLException("Failed to insert town member.");
        }
        return inserted;
    }

    public static @Nullable TownMemberDOO select(@NotNull Integer townId, @NotNull UUID player) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("town_member")
                .where("town_id = ? AND player_uuid = ?", townId, player.toString())
                .execute();
        if (res.isEmpty()) return null;
        return parse(res.get(0));
    }

    public static @NotNull List<TownMemberDOO> selectByTown(@NotNull Integer townId) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("town_member")
                .where("town_id = ?", townId)
                .execute();
        return res.stream().map(TownMemberDOO::parse).toList();
    }

    public static @NotNull List<TownMemberDOO> selectByPlayer(@NotNull UUID player) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("town_member")
                .where("player_uuid = ?", player.toString())
                .execute();
        return res.stream().map(TownMemberDOO::parse).toList();
    }

    public static @NotNull List<TownMemberDOO> selectAll() throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("town_member")
                .execute();
        return res.stream().map(TownMemberDOO::parse).toList();
    }

    /**
     * max-memberships metric: distinct towns the player formally belongs to.
     */
    public static int countTownsOf(@NotNull UUID player) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(new Field<?>[]{new FieldString("player_uuid")})
                .from("town_member")
                .where("player_uuid = ?", player.toString())
                .execute();
        return res.size();
    }

    public static void delete(@NotNull Integer townId, @NotNull UUID player) throws SQLException {
        Delete.delete().from("town_member")
                .where("town_id = ? AND player_uuid = ?", townId, player.toString())
                .execute();
    }

    public static void deleteByTown(@NotNull Integer townId) throws SQLException {
        Delete.delete().from("town_member").where("town_id = ?", townId).execute();
    }

    public @NotNull Integer getTownId() {
        return townId.getValue();
    }

    public @NotNull UUID getPlayerUuid() {
        return UUID.fromString(playerUuid.getValue());
    }

    public @NotNull TownRole getRole() {
        String v = role.getValue();
        return v == null ? TownRole.RESIDENT : TownRole.valueOf(v);
    }

    public @NotNull TownMemberDOO setRole(@NotNull TownRole newRole) throws SQLException {
        this.role.setValue(newRole.name());
        Update.update("town_member").set(this.role)
                .where("town_id = ? AND player_uuid = ?", townId.getValue(), playerUuid.getValue())
                .execute();
        return this;
    }

    public @NotNull MemberSource getSource() {
        String v = source.getValue();
        return v == null ? MemberSource.JOINED : MemberSource.valueOf(v);
    }

    public @Nullable Timestamp getJoinedAt() {
        return joinedAt.getValue();
    }

    private TownMemberDOO(Integer townId, UUID playerUuid, String role, String source, Timestamp joinedAt) {
        this.townId.setValue(townId);
        this.playerUuid.setValue(playerUuid.toString());
        this.role.setValue(role);
        this.source.setValue(source);
        this.joinedAt.setValue(joinedAt);
    }
}
