package site.bluearchive.xalarlogin.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bukkit.configuration.ConfigurationSection;

/**
 * 账号存储，后端可选 SQLite 或 MySQL（见 {@link Backend}）。
 * 所有方法都会做 IO，必须在异步线程调用。
 *
 * <p>单连接 + {@code synchronized}：查询量很小（进服查一次、登录写一次），
 * 串行化不构成瓶颈，省掉连接池依赖。MySQL 的连接会被服务端 {@code wait_timeout}
 * 掐断，所以每个操作都包在 {@link #execute} 里，失败时验一次连接并重连重试。
 */
public final class Database implements AutoCloseable {

    /** 账号数据快照。lastIp 为 null 表示从未记录过登录 IP。 */
    public record Account(String passwordHash, String lastIp) {
    }

    /** 表名会直接拼进 SQL，只允许标识符字符 */
    private static final Pattern SAFE_TABLE = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final String DEFAULT_TABLE = "accounts";

    @FunctionalInterface
    private interface SqlAction<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Backend backend;
    private final String url;
    private final String user;
    private final String password;
    private final String table;

    private Connection connection;

    private Database(Backend backend, String url, String user, String password, String table) throws SQLException {
        this.backend = backend;
        this.url = url;
        this.user = user;
        this.password = password;
        this.table = table;
        try {
            Class.forName(backend.driverClass());
        } catch (ClassNotFoundException e) {
            throw new SQLException("找不到 " + backend + " 的 JDBC 驱动（" + backend.driverClass()
                    + "），请确认运行在 Paper 服务端上", e);
        }
        reconnect();
        initSchema();
    }

    /**
     * 按 config.yml 的 {@code storage} 段建立存储。
     *
     * @param storage    config.yml 里的 storage 段，缺失时按 SQLite 处理
     * @param dataFolder 插件数据目录，SQLite 的 data.db 放这里
     */
    public static Database create(ConfigurationSection storage, File dataFolder) throws SQLException {
        String rawType = storage == null ? "sqlite" : storage.getString("type", "sqlite");
        Backend backend = Backend.parse(rawType);
        if (backend == null) {
            throw new SQLException("storage.type 只能是 sqlite 或 mysql，当前配置为: " + rawType);
        }
        if (backend == Backend.SQLITE) {
            if (!dataFolder.isDirectory() && !dataFolder.mkdirs()) {
                throw new SQLException("无法创建数据目录: " + dataFolder.getAbsolutePath());
            }
            File dbFile = new File(dataFolder, "data.db");
            return new Database(backend, "jdbc:sqlite:" + dbFile.getAbsolutePath(), null, null, DEFAULT_TABLE);
        }

        ConfigurationSection mysql = storage.getConfigurationSection("mysql");
        if (mysql == null) {
            throw new SQLException("storage.type 是 mysql，但 config.yml 里没有 storage.mysql 段");
        }
        String database = mysql.getString("database", "");
        if (database.isBlank()) {
            throw new SQLException("storage.mysql.database 不能为空");
        }
        String table = mysql.getString("table", DEFAULT_TABLE);
        if (!SAFE_TABLE.matcher(table).matches()) {
            throw new SQLException("storage.mysql.table 只能包含字母、数字和下划线，当前配置为: " + table);
        }
        String properties = mysql.getString("properties", "");
        String url = "jdbc:mysql://" + mysql.getString("host", "localhost")
                + ":" + mysql.getInt("port", 3306) + "/" + database
                + (properties.isBlank() ? "" : "?" + properties);
        return new Database(backend, url, mysql.getString("user", ""), mysql.getString("password", ""), table);
    }

    public Backend backend() {
        return backend;
    }

    private void reconnect() throws SQLException {
        closeQuietly();
        connection = user == null
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
    }

    private void closeQuietly() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 已经断了的连接关不上无所谓，反正马上要换新的
        }
        connection = null;
    }

    /**
     * 跑一条语句；连接已经失效时重连一次再试。
     *
     * <p>不在正常路径上调 isValid()——那是一次额外往返。只有真的抛异常了才去验，
     * 这样空闲被掐断的连接能自愈，而正常查询不用付代价。
     */
    private synchronized <T> T execute(SqlAction<T> action) throws SQLException {
        if (connection == null || connection.isClosed()) {
            reconnect();
        }
        try {
            return action.run(connection);
        } catch (SQLException first) {
            boolean usable;
            try {
                usable = connection != null && !connection.isClosed() && connection.isValid(2);
            } catch (SQLException e) {
                usable = false;
            }
            if (usable) {
                throw first;
            }
            reconnect();
            return action.run(connection);
        }
    }

    private void initSchema() throws SQLException {
        execute(conn -> {
            try (Statement statement = conn.createStatement()) {
                statement.execute(backend.createTable(table));
            }
            return null;
        });
        migrateAddLastIp();
    }

    /** 旧版本建的表没有 last_ip 列，补上。用 JDBC 元数据而非 PRAGMA，两种后端通用。 */
    private void migrateAddLastIp() throws SQLException {
        boolean hasLastIp = execute(conn -> {
            try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, table, "last_ip")) {
                return rs.next();
            }
        });
        if (!hasLastIp) {
            execute(conn -> {
                try (Statement statement = conn.createStatement()) {
                    statement.execute("ALTER TABLE " + table + " ADD COLUMN last_ip " + backend.ipColumnType());
                }
                return null;
            });
        }
    }

    /** @return 账号数据，未注册返回 null */
    public Account findAccount(UUID uuid) throws SQLException {
        return execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT password_hash, last_ip FROM " + table + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Account(rs.getString(1), rs.getString(2)) : null;
                }
            }
        });
    }

    /**
     * 写入新账号。用「冲突则忽略」而不是裸 INSERT：玩家在注册的异步处理途中退服重连时，
     * 新会话可能在这条 INSERT 落库前就查到「未注册」，之后再 /reg 就会撞主键冲突，
     * 让账号既注册不了也登录不了。返回 false 让调用方能把玩家导向 /a 而不是报错。
     *
     * @return true 表示插入成功，false 表示该 UUID 已有账号
     */
    public boolean register(UUID uuid, String name, String passwordHash, String ip) throws SQLException {
        return execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(backend.insertIgnoreInto() + table
                    + " (uuid, name, password_hash, registered_at, last_login, last_ip) VALUES (?, ?, ?, ?, ?, ?)")) {
                long now = System.currentTimeMillis();
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, passwordHash);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.setString(6, ip);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** 改密码同时清空 last_ip：密码变化后下次进服必须重新输密码，免密会话作废 */
    public void updatePassword(UUID uuid, String passwordHash) throws SQLException {
        execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + table + " SET password_hash = ?, last_ip = NULL WHERE uuid = ?")) {
                ps.setString(1, passwordHash);
                ps.setString(2, uuid.toString());
                return ps.executeUpdate();
            }
        });
    }

    /**
     * 管理员按玩家名改密码（不区分大小写），同样清空 last_ip。
     * 按名字而不是 UUID，是为了能给不在线的玩家改。
     *
     * @return 实际改动的记录数
     */
    public int updatePasswordByName(String name, String passwordHash) throws SQLException {
        return execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE " + table
                    + " SET password_hash = ?, last_ip = NULL WHERE lower(name) = lower(?)")) {
                ps.setString(1, passwordHash);
                ps.setString(2, name);
                return ps.executeUpdate();
            }
        });
    }

    /** 记录成功登录的时间与来源 IP（IP 用于同 IP 免密登录） */
    public void updateLastLogin(UUID uuid, String ip) throws SQLException {
        execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + table + " SET last_login = ?, last_ip = ? WHERE uuid = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, ip);
                ps.setString(3, uuid.toString());
                return ps.executeUpdate();
            }
        });
    }

    /**
     * 按玩家名删除账号（不区分大小写）。
     *
     * <p>离线模式的 UUID 由玩家名派生且区分大小写，所以 Steve 和 steve 是两个独立账号，
     * 这条语句会一并删掉。返回条数而非布尔值，好让管理员从回显里看出误删了几个。
     *
     * @return 实际删除的记录数
     */
    public int deleteByName(String name) throws SQLException {
        return execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + table + " WHERE lower(name) = lower(?)")) {
                ps.setString(1, name);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public synchronized void close() throws SQLException {
        if (connection != null) {
            Connection open = connection;
            connection = null;
            open.close();
        }
    }
}
