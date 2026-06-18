package heapo.session;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.Record;
import org.jooq.impl.DSL;

import java.util.*;

/** Records commands and their result storage pointers in the history table. */
public final class HistoryManager {

    public record Entry(int id, String command, long timestamp,
                        String bitsetFile, String sqlTable,
                        Integer input1, Integer input2) {}

    private static final Table<Record> T    = DSL.table(DSL.name("history"));
    private static final Field<Integer> ID  = DSL.field(DSL.name("id"),          Integer.class);
    private static final Field<String>  CMD = DSL.field(DSL.name("command"),     String.class);
    private static final Field<Long>    TS  = DSL.field(DSL.name("timestamp"),   Long.class);
    private static final Field<String>  BF  = DSL.field(DSL.name("bitset_file"), String.class);
    private static final Field<String>  ST  = DSL.field(DSL.name("sql_table"),   String.class);
    private static final Field<Integer> I1  = DSL.field(DSL.name("input1"),      Integer.class);
    private static final Field<Integer> I2  = DSL.field(DSL.name("input2"),      Integer.class);

    private final DSLContext ctx;

    public HistoryManager(DSLContext ctx) { this.ctx = ctx; }

    /** Inserts a history row and returns the generated id. */
    public int record(String command, long timestamp) {
        var rec = ctx.insertInto(T)
            .set(CMD, command).set(TS, timestamp)
            .returningResult(ID)
            .fetchOne();
        return rec.get(ID);
    }

    public void setBitsetFile(int id, String filename) {
        ctx.update(T).set(BF, filename).where(ID.eq(id)).execute();
    }

    public void setSqlTable(int id, String tableName) {
        ctx.update(T).set(ST, tableName).where(ID.eq(id)).execute();
    }

    public void setInputs(int id, Integer input1, Integer input2) {
        ctx.update(T).set(I1, input1).set(I2, input2).where(ID.eq(id)).execute();
    }

    public List<Entry> recent(int n) {
        return ctx.selectFrom(T)
            .orderBy(ID.desc())
            .limit(n)
            .fetch(r -> new Entry(r.get(ID), r.get(CMD), r.get(TS),
                                  r.get(BF), r.get(ST), r.get(I1), r.get(I2)));
    }

    /** Returns the most recent history entry that has a non-null storage pointer. */
    public Optional<Entry> lastWithStorage() {
        return ctx.selectFrom(T)
            .where(BF.isNotNull().or(ST.isNotNull()))
            .orderBy(ID.desc())
            .limit(1)
            .fetchOptional(r -> new Entry(r.get(ID), r.get(CMD), r.get(TS),
                                          r.get(BF), r.get(ST), r.get(I1), r.get(I2)));
    }

    /** Returns the most recent CALL or FORGET command, if any. */
    public Optional<Entry> lastUndoable() {
        return ctx.selectFrom(T)
            .where(DSL.upper(CMD).like("CALL %").or(DSL.upper(CMD).like("FORGET %")))
            .orderBy(ID.desc())
            .limit(1)
            .fetchOptional(r -> new Entry(r.get(ID), r.get(CMD), r.get(TS),
                                          r.get(BF), r.get(ST), r.get(I1), r.get(I2)));
    }
}
