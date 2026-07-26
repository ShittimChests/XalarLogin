package site.bluearchive.xalarlogin.storage;

import java.util.Locale;

/**
 * 存储后端及其 SQL 方言差异。
 *
 * <p>两个驱动都由 Paper 服务端自带（{@code libraries/} 下的 sqlite-jdbc 与 mysql-connector-j），
 * 所以切换后端不需要任何额外依赖，也不需要 shade。
 */
public enum Backend {

    /** 单文件 SQLite，零配置，适合单服 */
    SQLITE("org.sqlite.JDBC") {
        @Override
        String createTable(String table) {
            return """
                    CREATE TABLE IF NOT EXISTS %s (
                      uuid TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      password_hash TEXT NOT NULL,
                      registered_at INTEGER NOT NULL,
                      last_login INTEGER,
                      last_ip TEXT
                    )""".formatted(table);
        }

        @Override
        String insertIgnoreInto() {
            return "INSERT OR IGNORE INTO ";
        }

        @Override
        String ipColumnType() {
            return "TEXT";
        }
    },

    /** 外部 MySQL/MariaDB，适合多服共享账号 */
    MYSQL("com.mysql.cj.jdbc.Driver") {
        @Override
        String createTable(String table) {
            // uuid 用 VARCHAR(36) 而非 TEXT：MySQL 的 TEXT 不加长度前缀不能做主键。
            // last_ip 留 45 位以容纳 IPv6（含 ::ffff: 前缀的最长形式）。
            return """
                    CREATE TABLE IF NOT EXISTS %s (
                      uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                      name VARCHAR(32) NOT NULL,
                      password_hash VARCHAR(255) NOT NULL,
                      registered_at BIGINT NOT NULL,
                      last_login BIGINT,
                      last_ip VARCHAR(45)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""".formatted(table);
        }

        // INSERT IGNORE 会把主键冲突之外的错误也降级成警告，语义比想要的宽。
        // 但不能换成 INSERT ... ON DUPLICATE KEY UPDATE uuid = uuid：Connector/J 默认
        // useAffectedRows=false，返回的是「匹配行数」而非「改动行数」，冲突时也返回非 0，
        // register() 的「>0 即插入成功」就废了——玩家会被告知注册成功，实际库里还是旧密码。
        // INSERT IGNORE 冲突时稳定返回 0，这个契约才是 register() 能依赖的。
        @Override
        String insertIgnoreInto() {
            return "INSERT IGNORE INTO ";
        }

        @Override
        String ipColumnType() {
            return "VARCHAR(45)";
        }
    };

    private final String driverClass;

    Backend(String driverClass) {
        this.driverClass = driverClass;
    }

    public String driverClass() {
        return driverClass;
    }

    /** 建表语句，表名已经过 {@link Database} 的白名单校验 */
    abstract String createTable(String table);

    /** 主键冲突时静默跳过的插入写法 */
    abstract String insertIgnoreInto();

    /** 迁移补 last_ip 列时用的列类型 */
    abstract String ipColumnType();

    /** @return 配置里写的后端名，无法识别时返回 null */
    public static Backend parse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "sqlite" -> SQLITE;
            case "mysql", "mariadb" -> MYSQL;
            default -> null;
        };
    }
}
