package heapo.session;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.*;

/** Manages user-defined name bindings to history entries. */
public final class NamesManager {

    private static final Table<Record> T      = DSL.table(DSL.name("names"));
    private static final Field<String>  NAME  = DSL.field(DSL.name("name"),       String.class);
    private static final Field<Integer> HID   = DSL.field(DSL.name("history_id"), Integer.class);

    private final DSLContext ctx;

    public NamesManager(DSLContext ctx) { this.ctx = ctx; }

    /**
     * Bind {@code name} to {@code historyId}. Returns the previously-bound history ID if any
     * (used to record the displaced binding in the history row's input2 column).
     */
    public Optional<Integer> bind(String name, int historyId) {
        Optional<Integer> prev = resolve(name);
        ctx.insertInto(T).set(NAME, name).set(HID, historyId)
            .onConflict(NAME).doUpdate().set(HID, historyId)
            .execute();
        return prev;
    }

    /** Remove a name binding. The underlying storage is not deleted. */
    public void forget(String name) {
        ctx.deleteFrom(T).where(NAME.eq(name)).execute();
    }

    /** Returns the history ID bound to {@code name}, or empty if not found. */
    public Optional<Integer> resolve(String name) {
        return ctx.select(HID).from(T).where(NAME.eq(name))
            .fetchOptional(HID);
    }

    /** Returns all current name → history-id bindings, sorted by name. */
    public Map<String, Integer> all() {
        var result = new TreeMap<String, Integer>();
        ctx.select(NAME, HID).from(T).orderBy(NAME)
            .forEach(r -> result.put(r.get(NAME), r.get(HID)));
        return result;
    }
}
