package cn.lunadeer.dominion.doos;

import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import cn.lunadeer.dominion.utils.databse.FIelds.Field;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldInteger;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldLong;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldString;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldTimestamp;
import cn.lunadeer.dominion.utils.databse.syntax.Delete;
import cn.lunadeer.dominion.utils.databse.syntax.Insert;
import cn.lunadeer.dominion.utils.databse.syntax.Select;
import cn.lunadeer.dominion.utils.databse.syntax.Update;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Town aggregate root. Towns are cross-server entities: no server_id, the
 * shared database is the single source of truth and every backend keeps a
 * full read replica in TownCache.
 * <p>
 * balance is stored as BIGINT cents: the ORM's FieldFloat maps to 32-bit
 * REAL/FLOAT on all three dialects, which silently drifts on large sums.
 */
public class TownDOO {

    public enum TownStatus {
        ACTIVE,
        DISBANDED
    }

    /** One-way permission model transition for a town. */
    public enum PermissionMode {
        LEGACY,
        TEMPLATE_V1
    }

    private final FieldInteger id = new FieldInteger("id");
    private final FieldString name = new FieldString("name");
    private final FieldString founderUuid = new FieldString("founder_uuid");
    private final FieldString mayorUuid = new FieldString("mayor_uuid");
    private final FieldLong balanceCents = new FieldLong("balance", 0L);
    private final FieldString status = new FieldString("status", TownStatus.ACTIVE.name());
    private final FieldString permissionMode = new FieldString("permission_mode", PermissionMode.LEGACY.name());
    private final FieldInteger level = new FieldInteger("level", 1);
    private final FieldTimestamp createdAt = new FieldTimestamp("created_at");

    private static Field<?>[] fields() {
        return new Field<?>[]{
                new FieldInteger("id"),
                new FieldString("name"),
                new FieldString("founder_uuid"),
                new FieldString("mayor_uuid"),
                new FieldLong("balance"),
                new FieldString("status"),
                new FieldString("permission_mode"),
                new FieldInteger("level"),
                new FieldTimestamp("created_at"),
        };
    }

    private static TownDOO parse(Map<String, Field<?>> map) {
        return new TownDOO(
                (Integer) map.get("id").getValue(),
                (String) map.get("name").getValue(),
                UUID.fromString((String) map.get("founder_uuid").getValue()),
                UUID.fromString((String) map.get("mayor_uuid").getValue()),
                (Long) map.get("balance").getValue(),
                (String) map.get("status").getValue(),
                (String) map.get("permission_mode").getValue(),
                (Integer) map.get("level").getValue(),
                (Timestamp) map.get("created_at").getValue()
        );
    }

    public static @NotNull TownDOO insert(@NotNull String name, @NotNull UUID founder) throws SQLException {
        TownDOO town = new TownDOO(null, name, founder, founder, 0L, TownStatus.ACTIVE.name(),
                PermissionMode.TEMPLATE_V1.name(), 1, null);
        Map<String, Field<?>> res = Insert.insert()
                .into("town")
                .values(town.name, town.founderUuid, town.mayorUuid, town.balanceCents, town.status,
                        town.permissionMode, town.level)
                .returning(fields())
                .execute();
        if (res.isEmpty()) {
            throw new SQLException("Failed to insert town.");
        }
        return parse(res);
    }

    public static @Nullable TownDOO select(@NotNull Integer id) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("town")
                .where("id = ?", id)
                .execute();
        if (res.isEmpty()) return null;
        return parse(res.get(0));
    }

    public static @Nullable TownDOO select(@NotNull String name) throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("town")
                .where("name = ?", name)
                .execute();
        if (res.isEmpty()) return null;
        return parse(res.get(0));
    }

    public static @NotNull List<TownDOO> selectAll() throws SQLException {
        List<Map<String, Field<?>>> res = Select.select(fields())
                .from("town")
                .execute();
        return res.stream().map(TownDOO::parse).toList();
    }

    public static void deleteById(@NotNull Integer id) throws SQLException {
        // Explicit cleanup protects databases created by older SQLite
        // configurations where ON DELETE CASCADE was declared but disabled.
        Delete.delete().from("town_group_flag").where("town_id = ?", id).execute();
        Delete.delete().from("town_group").where("town_id = ?", id).execute();
        Delete.delete().from("town").where("id = ?", id).execute();
    }

    public @NotNull Integer getId() {
        return id.getValue();
    }

    public @NotNull String getName() {
        return name.getValue();
    }

    public @NotNull TownDOO setName(@NotNull String newName) throws SQLException {
        FieldString updatedName = new FieldString("name", newName);
        int affected = Update.update("town").set(updatedName).where("id = ?", id.getValue()).execute();
        if (affected != 1) {
            throw new SQLException("Town name update affected " + affected + " rows for town " + id.getValue());
        }
        this.name.setValue(newName);
        return this;
    }

    public @NotNull UUID getFounderUuid() {
        return UUID.fromString(founderUuid.getValue());
    }

    public @NotNull UUID getMayorUuid() {
        return UUID.fromString(mayorUuid.getValue());
    }

    public @NotNull TownDOO setMayor(@NotNull UUID mayor) throws SQLException {
        this.mayorUuid.setValue(mayor.toString());
        Update.update("town").set(this.mayorUuid).where("id = ?", id.getValue()).execute();
        return this;
    }

    /**
     * Current balance in currency units (stored as cents).
     */
    public double getBalance() {
        Long v = balanceCents.getValue();
        return (v == null ? 0L : v) / 100.0;
    }

    /**
     * Adjust the balance by a signed amount in currency units and persist.
     * The update is atomic in SQL (balance = balance + ?) so concurrent
     * deposits/charges on this or other backend servers never lose each
     * other's mutations; the local value is then re-read from the database.
     * Caller is responsible for validating sufficient funds.
     */
    public @NotNull TownDOO adjustBalance(double delta) throws SQLException {
        long cents = Math.round(delta * 100.0);
        String updateSql = "UPDATE town SET balance = balance + ? WHERE id = ?";
        try (Connection conn = DatabaseManager.instance.getConnection();
             PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setLong(1, cents);
            ps.setInt(2, id.getValue());
            ps.executeUpdate();
        }
        try (Connection conn = DatabaseManager.instance.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT balance FROM town WHERE id = ?")) {
            ps.setInt(1, id.getValue());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    this.balanceCents.setValue(rs.getLong(1));
                }
            }
        }
        return this;
    }

    /**
     * Atomically charge the given amount of cents, refusing overdraft:
     * the UPDATE carries a {@code balance >= ?} guard so concurrent
     * withdrawals (two managers, or two backends) can never drive the
     * balance negative — the loser gets {@code false} instead of money.
     * On success the local value is re-read from the database.
     */
    public boolean tryChargeCents(long cents) throws SQLException {
        if (cents < 0) {
            throw new IllegalArgumentException("cents must be >= 0");
        }
        int updated;
        try (Connection conn = DatabaseManager.instance.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE town SET balance = balance - ? WHERE id = ? AND balance >= ?")) {
            ps.setLong(1, cents);
            ps.setInt(2, id.getValue());
            ps.setLong(3, cents);
            updated = ps.executeUpdate();
        }
        // re-read either way so the local value reflects the database
        try (Connection conn = DatabaseManager.instance.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT balance FROM town WHERE id = ?")) {
            ps.setInt(1, id.getValue());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    this.balanceCents.setValue(rs.getLong(1));
                }
            }
        }
        return updated == 1;
    }

    public boolean canAfford(double amount) {
        return getBalance() + 1e-9 >= amount;
    }

    public @NotNull TownStatus getStatus() {
        String v = status.getValue();
        return v == null ? TownStatus.ACTIVE : TownStatus.valueOf(v);
    }

    public @NotNull PermissionMode getPermissionMode() {
        String value = permissionMode.getValue();
        if (value == null) return PermissionMode.LEGACY;
        try {
            return PermissionMode.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return PermissionMode.LEGACY;
        }
    }

    public boolean isTemplatePermissionMode() {
        return getPermissionMode() == PermissionMode.TEMPLATE_V1;
    }

    public int getLevel() {
        Integer value = level.getValue();
        return value == null ? 1 : value;
    }

    public @NotNull TownDOO setLevel(int newLevel) throws SQLException {
        this.level.setValue(newLevel);
        Update.update("town").set(this.level).where("id = ?", id.getValue()).execute();
        return this;
    }

    public @NotNull TownDOO setStatus(@NotNull TownStatus newStatus) throws SQLException {
        this.status.setValue(newStatus.name());
        Update.update("town").set(this.status).where("id = ?", id.getValue()).execute();
        return this;
    }

    public @Nullable Timestamp getCreatedAt() {
        return createdAt.getValue();
    }

    private TownDOO(Integer id, String name, UUID founderUuid, UUID mayorUuid,
                    Long balanceCents, String status, String permissionMode, Integer level,
                    Timestamp createdAt) {
        this.id.setValue(id);
        this.name.setValue(name);
        this.founderUuid.setValue(founderUuid.toString());
        this.mayorUuid.setValue(mayorUuid.toString());
        this.balanceCents.setValue(balanceCents);
        this.status.setValue(status);
        this.permissionMode.setValue(permissionMode == null
                ? PermissionMode.LEGACY.name() : permissionMode);
        this.level.setValue(level == null ? 1 : level);
        this.createdAt.setValue(createdAt);
    }
}
