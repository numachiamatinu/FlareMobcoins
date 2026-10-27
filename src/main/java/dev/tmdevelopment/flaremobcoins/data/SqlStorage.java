package dev.tmdevelopment.flaremobcoins.data;

import dev.tmdevelopment.flaremobcoins.util.Num;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * SQLite (data/data.db) or MySQL / MariaDB. Same table as 3.x: player, uuid, mobcoins, multiplier.
 * Changes are atomic (mobcoins = mobcoins + ?), so several servers can share one MySQL database.
 */
public final class SqlStorage implements Storage {

    private final String type;
    private final String url;
    private final Properties props = new Properties();
    private final String table;
    private final Logger log;
    private Connection connection;

    private SqlStorage(String type, String url, String table, Logger log) {
        this.type = type;
        this.url = url;
        this.table = table.replaceAll("[^A-Za-z0-9_]", "");
        this.log = log;
    }

    public static SqlStorage sqlite(File file, String table, Logger log) {
        return new SqlStorage("sqlite", "jdbc:sqlite:" + file.getAbsolutePath(), table, log);
    }

    public static SqlStorage mysql(boolean mariadb, String host, int port, String database, String user, String password,
                                   String flags, String table, Logger log) {
        String driver = mariadb && driverExists("org.mariadb.jdbc.Driver") ? "mariadb" : "mysql";
        String f = flags == null ? "" : flags.trim();
        if (f.startsWith("?")) f = f.substring(1);
        SqlStorage s = new SqlStorage(driver, "jdbc:" + driver + "://" + host + ":" + port + "/" + database
                + (f.isEmpty() ? "" : "?" + f), table, log);
        s.props.setProperty("user", user == null ? "" : user);
        s.props.setProperty("password", password == null ? "" : password);
        return s;
    }

    private static boolean driverExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean sqlite() {
        return type.equals("sqlite");
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String toString() {
        return type + " (" + table + ")";
    }

    // ------------------------------------------------------------------ setup

    @Override
    public void open() throws Exception {
        if (sqlite()) {
            Class.forName("org.sqlite.JDBC");
            File f = new File(url.substring("jdbc:sqlite:".length()));
            if (!f.getParentFile().exists() && !f.getParentFile().mkdirs()) throw new IllegalStateException("can't create " + f.getParent());
        } else if (type.equals("mariadb")) {
            Class.forName("org.mariadb.jdbc.Driver");
        } else if (!driverExists("com.mysql.cj.jdbc.Driver") && !driverExists("com.mysql.jdbc.Driver")) {
            throw new IllegalStateException("no MySQL driver in this server");
        }
        Connection c = connection();
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table
                    + " (player VARCHAR(100), uuid VARCHAR(100), mobcoins DOUBLE DEFAULT 0, multiplier DOUBLE DEFAULT 1)");
        }
        addMissingColumns(c);
        quiet(c, sqlite() ? "CREATE INDEX IF NOT EXISTS " + table + "_uuid ON " + table + " (uuid)"
                : "CREATE INDEX " + table + "_uuid ON " + table + " (uuid)");
        quiet(c, sqlite() ? "CREATE INDEX IF NOT EXISTS " + table + "_player ON " + table + " (player COLLATE NOCASE)"
                : "CREATE INDEX " + table + "_player ON " + table + " (player)");
        // 3.x created DOUBLE(10,2): max 99,999,999.99. Plain DOUBLE has no such limit (values are kept).
        if (!sqlite()) {
            quiet(c, "ALTER TABLE " + table + " MODIFY mobcoins DOUBLE DEFAULT 0");
            quiet(c, "ALTER TABLE " + table + " MODIFY multiplier DOUBLE DEFAULT 1");
        }
        dedupe(c);
        quiet(c, "CREATE TABLE IF NOT EXISTS " + table + "_purchases (who VARCHAR(36) NOT NULL, item VARCHAR(100) NOT NULL, "
                + "period VARCHAR(16) NOT NULL, amount INT NOT NULL, PRIMARY KEY (who, item, period))");
    }

    // ------------------------------------------------------------------ shop limits and stock

    @Override
    public int bought(String who, String item, String period) throws Exception {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT amount FROM " + table + "_purchases WHERE who = ? AND item = ? AND period = ?")) {
                ps.setString(1, who);
                ps.setString(2, item);
                ps.setString(3, period);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public boolean reserve(String who, String item, String period, int max) throws Exception {
        if (max <= 0) return false;
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement((sqlite() ? "INSERT OR IGNORE" : "INSERT IGNORE") + " INTO " + table
                    + "_purchases (who, item, period, amount) VALUES (?, ?, ?, 0)")) {
                ps.setString(1, who);
                ps.setString(2, item);
                ps.setString(3, period);
                ps.executeUpdate();
            }
            return null;
        });
        int changed = write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + "_purchases SET amount = amount + 1 "
                    + "WHERE who = ? AND item = ? AND period = ? AND amount < ?")) {
                ps.setString(1, who);
                ps.setString(2, item);
                ps.setString(3, period);
                ps.setInt(4, max);
                return ps.executeUpdate();
            }
        });
        return changed > 0;
    }

    @Override
    public void release(String who, String item, String period) throws Exception {
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + "_purchases SET amount = amount - 1 "
                    + "WHERE who = ? AND item = ? AND period = ? AND amount > 0")) {
                ps.setString(1, who);
                ps.setString(2, item);
                ps.setString(3, period);
                return ps.executeUpdate();
            }
        });
    }

    /** A table shared with another plugin (3.x used "flaretokens") may miss our columns. */
    private void addMissingColumns(Connection c) {
        Set<String> cols = new HashSet<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + table + " LIMIT 1")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnName(i).toLowerCase(Locale.ROOT));
        } catch (SQLException e) {
            return;
        }
        if (!cols.contains("player")) quiet(c, "ALTER TABLE " + table + " ADD COLUMN player VARCHAR(100)");
        if (!cols.contains("mobcoins")) {
            quiet(c, "ALTER TABLE " + table + " ADD COLUMN mobcoins DOUBLE DEFAULT 0");
            if (log != null) log.info("Added the mobcoins column to the " + table + " table.");
        }
        if (!cols.contains("multiplier")) quiet(c, "ALTER TABLE " + table + " ADD COLUMN multiplier DOUBLE DEFAULT 1");
    }

    private static void quiet(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException ignored) {
            // index already there / not supported
        }
    }

    /** 3.x could write a player twice: the row with the most mobcoins is kept. */
    private void dedupe(Connection c) {
        try {
            List<String> dupes = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid FROM " + table + " GROUP BY uuid HAVING COUNT(*) > 1")) {
                while (rs.next()) dupes.add(rs.getString(1));
            }
            if (dupes.isEmpty()) return;
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                for (String id : dupes) {
                    String name = null;
                    double coins = 0, mult = 1;
                    try (PreparedStatement ps = c.prepareStatement("SELECT player, mobcoins, multiplier FROM " + table
                            + " WHERE uuid = ? ORDER BY mobcoins DESC LIMIT 1")) {
                        ps.setString(1, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                name = rs.getString(1);
                                coins = rs.getDouble(2);
                                mult = rs.getDouble(3);
                            }
                        }
                    }
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                        ps.setString(1, id);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table
                            + " (player, uuid, mobcoins, multiplier) VALUES (?, ?, ?, ?)")) {
                        ps.setString(1, name == null ? "" : name);
                        ps.setString(2, id);
                        ps.setDouble(3, coins);
                        ps.setDouble(4, mult <= 0 ? 1 : mult);
                        ps.executeUpdate();
                    }
                }
                c.commit();
                if (log != null) log.info("Merged the duplicated rows of " + dupes.size() + " player(s) in the " + table + " table.");
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            if (log != null) log.warning("Could not merge duplicated rows: " + e.getMessage());
        }
    }

    private Connection connection() throws SQLException {
        if (connection != null) {
            boolean ok;
            try {
                ok = !connection.isClosed() && (sqlite() || connection.isValid(3));
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) return connection;
            try {
                connection.close();
            } catch (Throwable ignored) {
            }
        }
        connection = sqlite() ? DriverManager.getConnection(url) : DriverManager.getConnection(url, props);
        return connection;
    }

    @Override
    public void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (Throwable ignored) {
        }
        connection = null;
    }

    private interface Sql<T> {
        T run(Connection c) throws SQLException;
    }

    /** Reads are retried once on a new connection (MySQL closes idle connections). */
    private <T> T read(Sql<T> sql) throws Exception {
        try {
            return sql.run(connection());
        } catch (SQLException e) {
            if (sqlite()) throw e;
            close();
            return sql.run(connection());
        }
    }

    private <T> T write(Sql<T> sql) throws Exception {
        return sql.run(connection());
    }

    private static Account account(ResultSet rs) throws SQLException {
        UUID id;
        try {
            id = UUID.fromString(rs.getString(1));
        } catch (Exception e) {
            return null;
        }
        double mult = rs.getDouble(4);
        if (rs.wasNull() || mult <= 0) mult = 1;
        return new Account(id, rs.getString(2), Num.round(rs.getDouble(3)), mult);
    }

    // ------------------------------------------------------------------ accounts

    @Override
    public Account get(UUID uuid) throws Exception {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, player, mobcoins, multiplier FROM " + table
                    + " WHERE uuid = ? LIMIT 1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? account(rs) : null;
                }
            }
        });
    }

    @Override
    public boolean create(UUID uuid, String name, double start) throws Exception {
        if (get(uuid) != null) {
            if (name != null) updateName(uuid, name);
            return false;
        }
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table
                    + " (player, uuid, mobcoins, multiplier) VALUES (?, ?, ?, 1)")) {
                ps.setString(1, name == null ? "" : name);
                ps.setString(2, uuid.toString());
                ps.setDouble(3, Num.round(Math.max(0, start)));
                ps.executeUpdate();
            }
            return null;
        });
        return true;
    }

    @Override
    public double add(UUID uuid, String name, double delta, double start) throws Exception {
        create(uuid, name, start);
        write(c -> {
            String sql = "UPDATE " + table + " SET mobcoins = CASE WHEN ROUND(mobcoins + ?, 2) < 0 THEN 0 "
                    + "ELSE ROUND(mobcoins + ?, 2) END WHERE uuid = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setDouble(1, delta);
                ps.setDouble(2, delta);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
        Account a = get(uuid);
        return a == null ? 0 : a.mobcoins;
    }

    @Override
    public double take(UUID uuid, double amount) throws Exception {
        int changed = write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table
                    + " SET mobcoins = ROUND(mobcoins - ?, 2) WHERE uuid = ? AND mobcoins >= ?")) {
                ps.setDouble(1, amount);
                ps.setString(2, uuid.toString());
                ps.setDouble(3, amount - 0.001);
                return ps.executeUpdate();
            }
        });
        if (changed == 0) return -1;
        Account a = get(uuid);
        return a == null ? 0 : Math.max(0, a.mobcoins);
    }

    @Override
    public void set(UUID uuid, String name, double value) throws Exception {
        double v = Num.round(Math.max(0, value));
        int changed = write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET mobcoins = ? WHERE uuid = ?")) {
                ps.setDouble(1, v);
                ps.setString(2, uuid.toString());
                return ps.executeUpdate();
            }
        });
        if (changed == 0) {
            write(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table
                        + " (player, uuid, mobcoins, multiplier) VALUES (?, ?, ?, 1)")) {
                    ps.setString(1, name == null ? "" : name);
                    ps.setString(2, uuid.toString());
                    ps.setDouble(3, v);
                    ps.executeUpdate();
                }
                return null;
            });
        }
    }

    @Override
    public void setMultiplier(UUID uuid, String name, double multiplier, double start) throws Exception {
        create(uuid, name, start);
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET multiplier = ? WHERE uuid = ?")) {
                ps.setDouble(1, multiplier <= 0 ? 1 : multiplier);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void updateName(UUID uuid, String name) throws Exception {
        if (name == null) return;
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table
                    + " SET player = ? WHERE uuid = ? AND (player IS NULL OR player <> ?)")) {
                ps.setString(1, name);
                ps.setString(2, uuid.toString());
                ps.setString(3, name);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Account byName(String name) throws Exception {
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, player, mobcoins, multiplier FROM " + table
                    + " WHERE player = ?" + (sqlite() ? " COLLATE NOCASE" : "") + " LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? account(rs) : null;
                }
            }
        });
    }

    @Override
    public List<Account> top(int size) throws Exception {
        return read(c -> {
            List<Account> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, player, mobcoins, multiplier FROM " + table
                    + " ORDER BY mobcoins DESC LIMIT ?")) {
                ps.setInt(1, size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Account a = account(rs);
                        if (a != null) out.add(a);
                    }
                }
            }
            return out;
        });
    }

    @Override
    public List<Account> all() throws Exception {
        return read(c -> {
            List<Account> out = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid, player, mobcoins, multiplier FROM " + table)) {
                while (rs.next()) {
                    Account a = account(rs);
                    if (a != null) out.add(a);
                }
            }
            return out;
        });
    }

    @Override
    public int count() throws Exception {
        return read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }
}
