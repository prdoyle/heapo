package heapo.session;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** SQLite connection + schema lifecycle for the heapo session database. */
public final class SessionDb implements AutoCloseable {

    private final Connection     conn;
    private final HistoryManager history;
    private final NamesManager   names;
    private final UserTableManager tables;
    private final SqlRouter      sql;

    private SessionDb(Connection conn, DSLContext ctx) {
        this.conn    = conn;
        this.history = new HistoryManager(ctx);
        this.names   = new NamesManager(ctx);
        this.tables  = new UserTableManager(ctx);
        this.sql     = new SqlRouter(ctx);
    }

    public static SessionDb open(Path dbPath) throws SQLException {
        var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        conn.setAutoCommit(true);
        var ctx  = DSL.using(conn, SQLDialect.SQLITE);
        createSchema(ctx);
        return new SessionDb(conn, ctx);
    }

    public HistoryManager    history() { return history; }
    public NamesManager      names()   { return names;   }
    public UserTableManager  tables()  { return tables;  }
    public SqlRouter         sql()     { return sql;     }

    private static void createSchema(DSLContext ctx) {
        ctx.createTableIfNotExists("history")
            .column("id",          SQLDataType.INTEGER.identity(true).notNull())
            .column("command",     SQLDataType.CLOB.notNull())
            .column("timestamp",   SQLDataType.BIGINT.notNull())
            .column("bitset_file", SQLDataType.CLOB.nullable(true))
            .column("sql_table",   SQLDataType.CLOB.nullable(true))
            .column("input1",      SQLDataType.INTEGER.nullable(true))
            .column("input2",      SQLDataType.INTEGER.nullable(true))
            .constraint(DSL.constraint("pk_history").primaryKey("id"))
            .execute();

        ctx.createTableIfNotExists("names")
            .column("name",       SQLDataType.CLOB.notNull())
            .column("history_id", SQLDataType.INTEGER.notNull())
            .constraint(DSL.constraint("pk_names").primaryKey("name"))
            .execute();
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
