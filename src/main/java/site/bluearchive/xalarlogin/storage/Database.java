package site.bluearchive.xalarlogin.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * SQLite 账号存储。驱动由 Paper 服务端自带（org.sqlite.JDBC）。
 * 所有方法都会做磁盘 IO，必须在异步线程调用。
 */
public final class Database implements AutoCloseable {

    /** 账号数据快照。lastIp 为 null 表示从未记录过登录 IP。 */
    public record Account(String passwordHash, String lastIp) {
    }

    private final Connection connection;

    public Database(File dataFolder) throws SQLException {
        dataFolder.mkdirs();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("找不到 SQLite 驱动（org.sqlite.JDBC），请确认运行在 Paper 服务端上", e);
        }
        File dbFile = new File(dataFolder, "data.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                      uuid TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      password_hash TEXT NOT NULL,
                      registered_at INTEGER NOT NULL,
                      last_login INTEGER,
                      last_ip TEXT
                    )""");
        }
        migrateAddLastIp();
    }

    /** 旧版本建的表没有 last_ip 列，补上 */
    private void migrateAddLastIp() throws SQLException {
        boolean hasLastIp = false;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(accounts)")) {
            while (rs.next()) {
                if ("last_ip".equalsIgnoreCase(rs.getString("name"))) {
                    hasLastIp = true;
                }
            }
        }
        if (!hasLastIp) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE accounts ADD COLUMN last_ip TEXT");
            }
        }
    }

    /** @return 账号数据，未注册返回 null */
    public synchronized Account findAccount(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT password_hash, last_ip FROM accounts WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Account(rs.getString(1), rs.getString(2)) : null;
            }
        }
    }

    public synchronized void register(UUID uuid, String name, String passwordHash, String ip) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO accounts (uuid, name, password_hash, registered_at, last_login, last_ip) VALUES (?, ?, ?, ?, ?, ?)")) {
            long now = System.currentTimeMillis();
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, passwordHash);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.setString(6, ip);
            ps.executeUpdate();
        }
    }

    /** 改密码同时清空 last_ip：密码变化后下次进服必须重新输密码，免密会话作废 */
    public synchronized void updatePassword(UUID uuid, String passwordHash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE accounts SET password_hash = ?, last_ip = NULL WHERE uuid = ?")) {
            ps.setString(1, passwordHash);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** 记录成功登录的时间与来源 IP（IP 用于同 IP 免密登录） */
    public synchronized void updateLastLogin(UUID uuid, String ip) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE accounts SET last_login = ?, last_ip = ? WHERE uuid = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, ip);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** 按玩家名删除账号（不区分大小写）。@return 是否删除了记录 */
    public synchronized boolean deleteByName(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM accounts WHERE lower(name) = lower(?)")) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
