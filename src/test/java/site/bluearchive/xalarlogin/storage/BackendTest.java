package site.bluearchive.xalarlogin.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackendTest {

    @Test
    @DisplayName("后端名解析忽略大小写与空白，认不出的返回 null")
    void parse() {
        assertEquals(Backend.SQLITE, Backend.parse("sqlite"));
        assertEquals(Backend.SQLITE, Backend.parse("  SQLite "));
        assertEquals(Backend.MYSQL, Backend.parse("mysql"));
        assertEquals(Backend.MYSQL, Backend.parse("MariaDB"));
        assertNull(Backend.parse("postgres"));
        assertNull(Backend.parse(""));
        assertNull(Backend.parse(null));
    }

    @Test
    @DisplayName("MySQL 必须用 INSERT IGNORE，不能换成 ON DUPLICATE KEY UPDATE")
    void mysqlKeepsInsertIgnore() {
        // Connector/J 默认 useAffectedRows=false 返回「匹配行数」，ON DUPLICATE KEY UPDATE
        // 在冲突时也返回非 0，register() 赖以判断的「>0 即插入成功」就废了——玩家会被告知
        // 注册成功，实际库里还是旧密码。INSERT IGNORE 冲突时稳定返回 0。
        String insert = Backend.MYSQL.insertIgnoreInto();
        assertTrue(insert.contains("IGNORE"), insert);
        assertFalse(insert.toUpperCase(java.util.Locale.ROOT).contains("ON DUPLICATE"), insert);
        assertTrue(insert.endsWith(" "), "后面直接接表名，必须留分隔空格");
    }

    @Test
    @DisplayName("SQLite 用 INSERT OR IGNORE")
    void sqliteUsesInsertOrIgnore() {
        assertEquals("INSERT OR IGNORE INTO ", Backend.SQLITE.insertIgnoreInto());
    }

    @Test
    @DisplayName("建表语句把表名填进去，且两边列名一致")
    void createTableUsesGivenName() {
        for (Backend backend : Backend.values()) {
            String ddl = backend.createTable("my_accounts");
            assertTrue(ddl.contains("my_accounts"), backend + ": " + ddl);
            assertTrue(ddl.startsWith("CREATE TABLE IF NOT EXISTS"), backend + ": " + ddl);
            for (String column : new String[]{
                    "uuid", "name", "password_hash", "registered_at", "last_login", "last_ip"}) {
                assertTrue(ddl.contains(column), backend + " 缺列 " + column);
            }
        }
    }

    @Test
    @DisplayName("MySQL 的 uuid 主键不能是 TEXT，last_ip 要容得下 IPv6")
    void mysqlColumnTypes() {
        String ddl = Backend.MYSQL.createTable("accounts");
        assertTrue(ddl.contains("uuid VARCHAR(36)"), ddl);
        assertTrue(ddl.contains("PRIMARY KEY"), ddl);
        assertEquals("VARCHAR(45)", Backend.MYSQL.ipColumnType());
        assertEquals("TEXT", Backend.SQLITE.ipColumnType());
    }

    @Test
    @DisplayName("驱动类名与 Paper libraries/ 里自带的一致")
    void driverClasses() {
        assertEquals("org.sqlite.JDBC", Backend.SQLITE.driverClass());
        assertEquals("com.mysql.cj.jdbc.Driver", Backend.MYSQL.driverClass());
    }
}
