package cn.lunadeer.dominion.utils.databse.FIelds;

import cn.lunadeer.dominion.utils.databse.DatabaseManager;

import java.util.List;

public class FieldLong extends Field<Long> {

    private Long value;

    public FieldLong(String name) {
        super(name);
    }

    public FieldLong(String name, Long value) {
        super(name);
        this.value = value;
    }

    @Override
    public String getSqlTypeStr() {
        return getTypeStrings().get(0);
    }

    @Override
    public String getUnifyTypeStr() {
        return "LONG";
    }

    @Override
    public Long getValue() {
        return value;
    }

    @Override
    public Field<Long> setValue(Long value) {
        this.value = value;
        return this;
    }

    public static List<String> getTypeStrings() {
        return switch (DatabaseManager.instance.getType()) {
            // MySQL 8 reports BIGINT, while MySQL 5.7/MariaDB commonly
            // retain the legacy display width and report BIGINT(20).
            // The width is presentation metadata, not a different value
            // range, so both map to the same Java Long field.
            case MYSQL -> List.of("BIGINT", "BIGINT(20)");
            case SQLITE -> List.of("BIGINT");
            case PGSQL -> List.of("BIGINT");
            default ->
                    throw new UnsupportedOperationException("Database type: " + DatabaseManager.instance.getType() + " not supported FieldString");
        };
    }
}
