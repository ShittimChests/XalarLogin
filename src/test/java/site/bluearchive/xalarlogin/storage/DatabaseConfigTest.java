package site.bluearchive.xalarlogin.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.SQLException;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * config.yml 的 storage 段校验。
 *
 * <p>两个 JDBC 驱动都不在测试 classpath 上（运行时由 Paper 的 libraries/ 提供），
 * 所以「配置合法」的表现是报驱动缺失——校验分支跑完了才走到连接那一步。
 * 靠这个差别就能把校验逻辑全测掉，不需要真的连数据库。
 */
class DatabaseConfigTest {

    private static final String DRIVER_MISSING = "JDBC 驱动";

    private static ConfigurationSection mysqlConfig() {
        MemoryConfiguration root = new MemoryConfiguration();
        ConfigurationSection storage = root.createSection("storage");
        storage.set("type", "mysql");
        ConfigurationSection mysql = storage.createSection("mysql");
        mysql.set("host", "db.example.com");
        mysql.set("port", 3306);
        mysql.set("database", "xalarlogin");
        mysql.set("user", "xalarlogin");
        mysql.set("password", "secret");
        mysql.set("table", "accounts");
        mysql.set("properties", "sslMode=PREFERRED");
        return storage;
    }

    private static String messageOf(ConfigurationSection storage, File dataFolder) {
        return assertThrows(SQLException.class, () -> Database.create(storage, dataFolder)).getMessage();
    }

    // ---------- 后端选择 ----------

    @Test
    @DisplayName("认不出的后端名直接报错，不静默退回 SQLite")
    void unknownBackendIsRejected(@TempDir File dataFolder) {
        ConfigurationSection storage = mysqlConfig();
        storage.set("type", "postgres");
        assertTrue(messageOf(storage, dataFolder).contains("storage.type"));
    }

    @Test
    @DisplayName("storage 段缺失时按 SQLite 处理")
    void missingStorageSectionDefaultsToSqlite(@TempDir File dataFolder) {
        assertTrue(messageOf(null, dataFolder).contains(DRIVER_MISSING));
    }

    @Test
    @DisplayName("mariadb 是 mysql 的别名")
    void mariadbIsAnAlias(@TempDir File dataFolder) {
        ConfigurationSection storage = mysqlConfig();
        storage.set("type", "MariaDB");
        assertTrue(messageOf(storage, dataFolder).contains(DRIVER_MISSING));
        assertEquals(Backend.MYSQL, Backend.parse("  MariaDB  "));
    }

    @Test
    @DisplayName("type 是 mysql 却没有 mysql 段")
    void missingMysqlSection(@TempDir File dataFolder) {
        MemoryConfiguration root = new MemoryConfiguration();
        ConfigurationSection storage = root.createSection("storage");
        storage.set("type", "mysql");
        assertTrue(messageOf(storage, dataFolder).contains("storage.mysql"));
    }

    // ---------- 拼进 JDBC URL 的三个字段 ----------

    @Test
    @DisplayName("库名不能为空")
    void blankDatabaseIsRejected(@TempDir File dataFolder) {
        ConfigurationSection storage = mysqlConfig();
        storage.getConfigurationSection("mysql").set("database", "   ");
        assertTrue(messageOf(storage, dataFolder).contains("database 不能为空"));
    }

    @Test
    @DisplayName("库名里的 ? 会被拒绝——否则可以绕过 properties 黑名单")
    void databaseCannotSmuggleProperties(@TempDir File dataFolder) {
        ConfigurationSection storage = mysqlConfig();
        storage.getConfigurationSection("mysql")
                .set("database", "xalarlogin?allowLoadLocalInfile=true");
        assertTrue(messageOf(storage, dataFolder).contains("storage.mysql.database"));
    }

    @Test
    @DisplayName("主机名里的 / 与 ? 同样会被拒绝")
    void hostCannotSmuggleProperties(@TempDir File dataFolder) {
        ConfigurationSection storage = mysqlConfig();
        storage.getConfigurationSection("mysql")
                .set("host", "evil.example.com/db?autoDeserialize=true#");
        assertTrue(messageOf(storage, dataFolder).contains("storage.mysql.host"));
    }

    @Test
    @DisplayName("方括号包起来的 IPv6 是合法主机")
    void bracketedIpv6IsAccepted(@TempDir File dataFolder) {
        ConfigurationSection storage = mysqlConfig();
        storage.getConfigurationSection("mysql").set("host", "[2001:db8::1]");
        assertTrue(messageOf(storage, dataFolder).contains(DRIVER_MISSING));
    }

    @Test
    @DisplayName("端口要在合法范围内")
    void portRangeIsChecked(@TempDir File dataFolder) {
        ConfigurationSection storage = mysqlConfig();
        storage.getConfigurationSection("mysql").set("port", 70000);
        assertTrue(messageOf(storage, dataFolder).contains("storage.mysql.port"));
    }

    @Test
    @DisplayName("表名只允许标识符字符")
    void tableNameIsWhitelisted(@TempDir File dataFolder) {
        ConfigurationSection storage = mysqlConfig();
        storage.getConfigurationSection("mysql").set("table", "accounts; DROP TABLE accounts");
        assertTrue(messageOf(storage, dataFolder).contains("storage.mysql.table"));
    }

    // ---------- properties 黑名单 ----------

    @Test
    @DisplayName("黑名单参数命中即报错，并指名是哪一个")
    void bannedPropertiesAreRejected(@TempDir File dataFolder) {
        String[] banned = {
                "autoDeserialize=true",
                "allowLoadLocalInfile=true",
                "allowUrlInLocalInfile=true",
                "allowMultiQueries=true",
                "databaseTerm=SCHEMA",
        };
        for (String pair : banned) {
            ConfigurationSection storage = mysqlConfig();
            storage.getConfigurationSection("mysql")
                    .set("properties", "sslMode=PREFERRED&" + pair);
            String message = messageOf(storage, dataFolder);
            String key = pair.split("=")[0].toLowerCase(java.util.Locale.ROOT);
            assertTrue(message.contains(key), pair + " 应被拒绝，实际报错: " + message);
        }
    }

    @Test
    @DisplayName("黑名单不区分大小写与空白")
    void bannedPropertiesIgnoreCaseAndSpaces() {
        MemoryConfiguration root = new MemoryConfiguration();
        ConfigurationSection storage = root.createSection("storage");
        storage.set("type", "mysql");
        ConfigurationSection mysql = storage.createSection("mysql");
        mysql.set("database", "x");
        mysql.set("properties", " AUTODESERIALIZE =true");
        assertTrue(assertThrows(SQLException.class,
                () -> Database.create(storage, new File("unused"))).getMessage()
                .contains("autodeserialize"));
    }

    // ---------- 超时默认值 ----------

    @Test
    @DisplayName("没配超时就补上默认值")
    void timeoutDefaultsAreInjected() {
        String result = Database.withTimeoutDefaults("sslMode=PREFERRED");
        assertTrue(result.contains("connectTimeout=5000"), result);
        assertTrue(result.contains("socketTimeout=30000"), result);
        assertTrue(result.contains("sslMode=PREFERRED"), result);
    }

    @Test
    @DisplayName("properties 为空时也不会拼出空查询串")
    void timeoutDefaultsWithBlankProperties() {
        assertEquals("connectTimeout=5000&socketTimeout=30000", Database.withTimeoutDefaults(""));
    }

    @Test
    @DisplayName("管理员显式配了就不覆盖")
    void explicitTimeoutsWin() {
        String result = Database.withTimeoutDefaults("socketTimeout=1000&connectTimeout=2000");
        assertEquals("socketTimeout=1000&connectTimeout=2000", result);

        String partial = Database.withTimeoutDefaults("socketTimeout=1000");
        assertTrue(partial.contains("connectTimeout=5000"), partial);
        assertTrue(partial.contains("socketTimeout=1000"), partial);
        assertEquals(1, partial.split("socketTimeout=", -1).length - 1, "不该出现两个 socketTimeout");
    }
}
