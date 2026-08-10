package cn.lunadeer.dominion.doos;

import cn.lunadeer.dominion.utils.databse.FIelds.Field;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldInteger;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldString;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldTimestamp;
import cn.lunadeer.dominion.utils.databse.syntax.Select;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Town-level group definition.  Permission flags deliberately do not live
 * here: each outpost owns an independent dominion_group mirror whose
 * town_group_id points back to this row.
 */
public class TownGroupDOO {

    private final FieldInteger id = new FieldInteger("id");
    private final FieldInteger townId = new FieldInteger("town_id");
    private final FieldString name = new FieldString("name");
    private final FieldString displayName = new FieldString("display_name");
    private final FieldInteger priority = new FieldInteger("priority", 0);
    private final FieldTimestamp createdAt = new FieldTimestamp("created_at");

    private static Field<?>[] fields() {
        return new Field<?>[]{
                new FieldInteger("id"),
                new FieldInteger("town_id"),
                new FieldString("name"),
                new FieldString("display_name"),
                new FieldInteger("priority"),
                new FieldTimestamp("created_at")
        };
    }

    private static TownGroupDOO parse(Map<String, Field<?>> row) {
        return new TownGroupDOO(
                (Integer) row.get("id").getValue(),
                (Integer) row.get("town_id").getValue(),
                (String) row.get("name").getValue(),
                (String) row.get("display_name").getValue(),
                (Integer) row.get("priority").getValue(),
                (Timestamp) row.get("created_at").getValue()
        );
    }

    public static @Nullable TownGroupDOO select(int id) throws SQLException {
        List<Map<String, Field<?>>> rows = Select.select(fields())
                .from("town_group").where("id = ?", id).execute();
        return rows.isEmpty() ? null : parse(rows.get(0));
    }

    public static @Nullable TownGroupDOO select(int townId, @NotNull String name) throws SQLException {
        List<Map<String, Field<?>>> rows = Select.select(fields())
                .from("town_group")
                .where("town_id = ? AND name = ?", townId, name.toLowerCase(Locale.ROOT))
                .execute();
        return rows.isEmpty() ? null : parse(rows.get(0));
    }

    public static @NotNull List<TownGroupDOO> selectByTown(int townId) throws SQLException {
        return Select.select(fields()).from("town_group")
                .where("town_id = ?", townId).execute().stream()
                .map(TownGroupDOO::parse).toList();
    }

    public static @NotNull List<TownGroupDOO> selectAll() throws SQLException {
        return Select.select(fields()).from("town_group").execute().stream()
                .map(TownGroupDOO::parse).toList();
    }

    public int getId() {
        return id.getValue();
    }

    public int getTownId() {
        return townId.getValue();
    }

    public @NotNull String getName() {
        return name.getValue();
    }

    public @NotNull String getDisplayName() {
        String value = displayName.getValue();
        return value == null || value.isBlank() ? getName() : value;
    }

    public int getPriority() {
        Integer value = priority.getValue();
        return value == null ? 0 : value;
    }

    public @Nullable Timestamp getCreatedAt() {
        return createdAt.getValue();
    }

    private TownGroupDOO(Integer id, Integer townId, String name, String displayName,
                         Integer priority, Timestamp createdAt) {
        this.id.setValue(id);
        this.townId.setValue(townId);
        this.name.setValue(name);
        this.displayName.setValue(displayName);
        this.priority.setValue(priority);
        this.createdAt.setValue(createdAt);
    }
}
