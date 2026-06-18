package heapo.session;

import heapo.model.TableAnswer;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.util.List;
import java.util.UUID;

/**
 * Executes raw SQL against the session SQLite database and stores the result in a new table.
 * Detects {@code SELECT} / {@code WITH} prefix to decide routing.
 */
public final class SqlRouter {

    private final DSLContext ctx;

    SqlRouter(DSLContext ctx) { this.ctx = ctx; }

    /** Returns true if the input looks like a SQL query rather than a DSL command. */
    public static boolean isSql(String input) {
        String upper = input.stripLeading().toUpperCase();
        return upper.startsWith("SELECT") || upper.startsWith("WITH");
    }

    /**
     * Execute {@code sql} and materialise its results into a new auto-named SQLite table.
     * Returns a {@link TableAnswer} pointing at the new table.
     */
    public TableAnswer execute(String sql) {
        String destTable = "t_" + UUID.randomUUID().toString().replace("-", "");

        // Fetch the result of the user's query
        var result = ctx.fetch(sql);

        if (result.isEmpty()) {
            // Create an empty table with a placeholder column
            ctx.createTable(destTable)
                .column("_empty", SQLDataType.INTEGER)
                .execute();
            return new TableAnswer(destTable, 0);
        }

        // Build CREATE TABLE from the result's fields
        List<Field<?>> fields = List.of(result.fields());
        var create = ctx.createTable(destTable);
        var creator = create.column(fields.get(0).getName(), SQLDataType.CLOB);
        for (int i = 1; i < fields.size(); i++) {
            creator = creator.column(fields.get(i).getName(), SQLDataType.CLOB);
        }
        creator.execute();

        // Insert rows
        var t = DSL.table(DSL.name(destTable));
        for (var row : result) {
            var insert = ctx.insertInto(t);
            var step = insert.set(DSL.field(DSL.name(fields.get(0).getName()), String.class),
                                  stringify(row.get(0)));
            for (int i = 1; i < fields.size(); i++) {
                step = step.set(DSL.field(DSL.name(fields.get(i).getName()), String.class),
                                stringify(row.get(i)));
            }
            step.execute();
        }

        return new TableAnswer(destTable, result.size());
    }

    private static String stringify(Object v) {
        return v == null ? null : v.toString();
    }
}
