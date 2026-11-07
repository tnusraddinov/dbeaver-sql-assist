package com.sqlassist.dbeaver.ui.validation;

import com.sqlassist.dbeaver.parser.SqlToken;
import com.sqlassist.dbeaver.parser.SqlTokenizer;
import com.sqlassist.dbeaver.validation.ColumnMeta;
import com.sqlassist.dbeaver.validation.TableMeta;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLSearchUtils;
import org.jkiss.dbeaver.model.sql.parser.SQLIdentifierDetector;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a table name written in an INSERT into {@link TableMeta} using DBeaver's
 * navigator model (the same lookup SQL completion uses: default catalog/schema,
 * quoting and identifier case rules of the dialect are honoured).
 * Results, including "not found", are cached briefly so typing does not hammer the database.
 * One shared instance serves validation (Module 3) and column hints (Module 4).
 */
public final class TableMetadataResolver {

    private static final long TTL_MS = 30_000;
    private static final int MAX_ENTRIES = 1_000;
    private static final TableMetadataResolver SHARED = new TableMetadataResolver();

    /** Cache key: the same table name means different tables in different connections/schemas. */
    private record Key(DBCExecutionContext context, String name) { }

    private record Entry(Optional<TableMeta> table, long loadedAt) { }

    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    private TableMetadataResolver() {
    }

    public static TableMetadataResolver shared() {
        return SHARED;
    }

    public Optional<TableMeta> resolve(DBRProgressMonitor monitor, DBCExecutionContext context, String tableName) {
        Key key = new Key(context, tableName.toLowerCase(Locale.ROOT));
        Entry entry = cache.get(key);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.loadedAt() < TTL_MS) {
            return entry.table();
        }
        Optional<TableMeta> table = load(monitor, context, tableName);
        if (cache.size() >= MAX_ENTRIES) {
            cache.entrySet().removeIf(e -> now - e.getValue().loadedAt() >= TTL_MS || !e.getKey().context().isConnected());
        }
        cache.put(key, new Entry(table, now));
        return table;
    }

    private static Optional<TableMeta> load(DBRProgressMonitor monitor, DBCExecutionContext context, String tableName) {
        try {
            DBPDataSource dataSource = context.getDataSource();
            DBSObjectContainer container = DBUtils.getAdapter(DBSObjectContainer.class, dataSource);
            if (container == null) {
                return Optional.empty();
            }
            DBSObject object = SQLSearchUtils.findObjectByFQN(monitor, container, context, nameParts(tableName),
                    false, new SQLIdentifierDetector(dataSource.getSQLDialect()));
            if (!(object instanceof DBSEntity entity)) {
                return Optional.empty();
            }
            List<? extends DBSEntityAttribute> attributes = entity.getAttributes(monitor);
            if (attributes == null || attributes.isEmpty()) {
                return Optional.empty();
            }
            List<ColumnMeta> columns = new ArrayList<>();
            for (DBSEntityAttribute a : attributes) {
                if (!DBUtils.isHiddenObject(a)) {
                    columns.add(toColumn(a));
                }
            }
            return Optional.of(new TableMeta(entity.getName(), List.copyOf(columns)));
        } catch (Exception e) {
            // Missing privileges, dropped connection, unsupported driver: validate nothing.
            return Optional.empty();
        }
    }

    /** "public"."users" -> ["public", "users"], keeping the quotes for DBeaver to interpret. */
    private static List<String> nameParts(String tableName) {
        List<String> parts = new ArrayList<>();
        for (SqlToken t : SqlTokenizer.tokenize(tableName)) {
            if (t.isIdentifier()) {
                parts.add(t.text());
            }
        }
        return parts;
    }

    private static ColumnMeta toColumn(DBSEntityAttribute a) {
        String typeName = a.getTypeName() == null ? "?" : a.getTypeName();
        String lower = typeName.toLowerCase(Locale.ROOT);
        ColumnMeta.Kind kind = kindOf(a.getDataKind(), a.getTypeID(), lower, a.getScale());
        long maxLength = -1;
        if (kind == ColumnMeta.Kind.STRING && lower.contains("char") && a.getMaxLength() > 0
                && a.getMaxLength() < Integer.MAX_VALUE) {
            maxLength = a.getMaxLength(); // CHAR/VARCHAR/NVARCHAR/VARCHAR2; TEXT/CLOB are unbounded
        }
        String defaultValue = a.getDefaultValue();
        return new ColumnMeta(a.getName(), typeName, kind, maxLength, a.isRequired(),
                defaultValue != null && !defaultValue.isBlank(), a.isAutoGenerated());
    }

    private static ColumnMeta.Kind kindOf(DBPDataKind dataKind, int typeId, String typeName, Integer scale) {
        if (dataKind == null) {
            return ColumnMeta.Kind.OTHER;
        }
        return switch (dataKind) {
            case NUMERIC -> {
                boolean integer = typeId == Types.TINYINT || typeId == Types.SMALLINT || typeId == Types.INTEGER
                        || typeId == Types.BIGINT || typeName.contains("int") || typeName.contains("serial");
                yield integer ? ColumnMeta.Kind.INTEGER : ColumnMeta.Kind.DECIMAL;
            }
            case STRING -> ColumnMeta.Kind.STRING;
            case BOOLEAN -> ColumnMeta.Kind.BOOLEAN;
            case DATETIME -> ColumnMeta.Kind.DATETIME;
            case BINARY -> ColumnMeta.Kind.BINARY;
            default -> ColumnMeta.Kind.OTHER;
        };
    }
}
