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

    private final FieldInteger id = new FieldInteger("id");
    private final FieldString name = new FieldString("name");
    private final FieldString founderUuid = new FieldString("founder_uuid");
    private final FieldString mayorUuid = new FieldString("mayor_uuid");
    private final FieldLong balanceCents = new FieldLong("balance", 0L);
    private final FieldString status = new FieldString("status", TownStatus.ACTIVE.name());
    private final FieldTimestamp createdAt = new FieldTimestamp("created_at");

    private static Field<?>[] fields() {
        return new Field<?>[]{
                new FieldInteger("id"),
                new FieldString("name"),
                new FieldString("founder_uuid"),
                new FieldString("mayor_uuid"),
                new FieldLong("balance"),
                new FieldString("status"),
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
                (Timestamp) map.get("created_at").getValue()
        );
    }

    public static @NotNull TownDOO insert(@NotNull String name, @NotNull UUID founder) throws SQLException {
        TownDOO town = new TownDOO(null, name, founder, founder, 0L, TownStatus.ACTIVE.name(), null);
        Map<String, Field<?>> res = Insert.insert()
                .into("town")
                .values(town.name, town.founderUuid, town.mayorUuid, town.balanceCents, town.status)
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
        Delete.delete().from("town").where("id = ?", id).execute();
    }

    public @NotNull Integer getId() {
        return id.getValue();
    }

    public @NotNull String getName() {
        return name.getValue();
    }

    public @NotNull TownDOO setName(@NotNull String newName) throws SQLException {
        this.name.setValue(newName);
        Update.update("town").set(this.name).where("id = ?", id.getValue()).execute();
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

    public boolean canAfford(double amount) {
        return getBalance() + 1e-9 >= amount;
    }

    public @NotNull TownStatus getStatus() {
        String v = status.getValue();
        return v == null ? TownStatus.ACTIVE : TownStatus.valueOf(v);
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
                    Long balanceCents, String status, Timestamp createdAt) {
        this.id.setValue(id);
        this.name.setValue(name);
        this.founderUuid.setValue(founderUuid.toString());
        this.mayorUuid.setValue(mayorUuid.toString());
        this.balanceCents.setValue(balanceCents);
        this.status.setValue(status);
        this.createdAt.setValue(createdAt);
    }
}
