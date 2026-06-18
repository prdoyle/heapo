package heapo.session;

import heapo.model.TopNRow;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.util.List;
import java.util.UUID;

/** Creates and manages ephemeral SQLite tables for query results. */
public final class UserTableManager {

    private final DSLContext ctx;

    public UserTableManager(DSLContext ctx) { this.ctx = ctx; }

    /**
     * Writes query result rows to a new SQLite table with an auto-generated name.
     * Returns the generated table name.
     */
    public String writeTopNResult(List<TopNRow> rows) {
        String tableName = "t_" + UUID.randomUUID().toString().replace("-", "");

        ctx.createTable(tableName)
            .column("rank",          SQLDataType.INTEGER.notNull())
            .column("dense_id",      SQLDataType.INTEGER.notNull())
            .column("class_name",    SQLDataType.CLOB.notNull())
            .column("retained_size", SQLDataType.BIGINT.notNull())
            .column("shallow_size",  SQLDataType.BIGINT.notNull())
            .execute();

        var t = DSL.table(DSL.name(tableName));
        for (var row : rows) {
            ctx.insertInto(t)
                .set(DSL.field(DSL.name("rank"),          Integer.class), row.rank())
                .set(DSL.field(DSL.name("dense_id"),      Integer.class), row.denseId())
                .set(DSL.field(DSL.name("class_name"),    String.class),  row.className())
                .set(DSL.field(DSL.name("retained_size"), Long.class),    row.retainedSize())
                .set(DSL.field(DSL.name("shallow_size"),  Long.class),    row.shallowSize())
                .execute();
        }


        return tableName;
    }

    /** Drop a previously-created user table. */
    public void drop(String tableName) {
        ctx.dropTableIfExists(tableName).execute();
    }
}
