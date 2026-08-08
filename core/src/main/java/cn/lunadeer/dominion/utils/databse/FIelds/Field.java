package cn.lunadeer.dominion.utils.databse.FIelds;


import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class Field<T> {
    private final String name;

    public Field(String name) {
        this.name = name;
    }

    public static Field<?> fieldOfSqlType(String name, String typeString) {
        if (FieldBoolean.getTypeStrings().stream().anyMatch(s -> s.equalsIgnoreCase(typeString))) {
            return new FieldBoolean(name);
        }
        if (FieldFloat.getTypeStrings().stream().anyMatch(s -> s.equalsIgnoreCase(typeString))) {
            return new FieldFloat(name);
        }
        if (FieldInteger.getTypeStrings().stream().anyMatch(s -> s.equalsIgnoreCase(typeString))) {
            return new FieldInteger(name);
        }
        if (FieldLong.getTypeStrings().stream().anyMatch(s -> s.equalsIgnoreCase(typeString))) {
            return new FieldLong(name);
        }
        if (FieldString.getTypeStrings().stream().anyMatch(s -> s.equalsIgnoreCase(typeString))) {
            return new FieldString(name);
        }
        if (FieldTimestamp.getTypeStrings().stream().anyMatch(s -> s.equalsIgnoreCase(typeString))) {
            return new FieldTimestamp(name);
        }
        throw new IllegalArgumentException("Unsupported type string: " + typeString + " for field: " + name + " with database type: " + DatabaseManager.instance.getType());
    }

    public static Field<?> getFromResultSet(@NotNull Field<?> column, ResultSet resultSet) {
        try {
            String name = column.getName();
            if (column instanceof FieldString) {
                return new FieldString(name, resultSet.getString(name));
            }
            // Wrapper getters + wasNull: SQL NULL must stay null, not become
            // 0/false (nullable columns like dominion.town_id depend on it).
            if (column instanceof FieldInteger) {
                int v = resultSet.getInt(name);
                return new FieldInteger(name, resultSet.wasNull() ? null : v);
            }
            if (column instanceof FieldLong) {
                long v = resultSet.getLong(name);
                return new FieldLong(name, resultSet.wasNull() ? null : v);
            }
            if (column instanceof FieldFloat) {
                float v = resultSet.getFloat(name);
                return new FieldFloat(name, resultSet.wasNull() ? null : v);
            }
            if (column instanceof FieldBoolean) {
                boolean v = resultSet.getBoolean(name);
                return new FieldBoolean(name, resultSet.wasNull() ? null : v);
            }
            if (column instanceof FieldTimestamp) {
                return new FieldTimestamp(name, resultSet.getTimestamp(name));
            }
            throw new IllegalArgumentException("Unsupported field type: " + column.getClass().getSimpleName());
        } catch (Exception e) {
            XLogger.error(e);
            throw new RuntimeException(e);
        }
    }

    public String getName() {
        return name;
    }

    public abstract String getSqlTypeStr();

    public abstract String getUnifyTypeStr();

    public abstract T getValue();

    public abstract Field<T> setValue(T value);

    public static List<Map<String, Field<?>>> getFromResultSet(@Nullable Field<?>[] columns, ResultSet res) throws SQLException {
        if (columns != null && columns.length > 0) {
            List<Map<String, Field<?>>> result = new ArrayList<>();
            while (res.next()) {
                Map<String, Field<?>> row = new java.util.HashMap<>();
                for (Field<?> column : columns) {
                    if (column == null) {
                        continue;
                    }
                    Field<?> fieldContent = Field.getFromResultSet(column, res);
                    row.put(fieldContent.getName(), fieldContent);
                }
                result.add(row);
            }
            return result;
        } else {
            return new ArrayList<>();
        }
    }
}
