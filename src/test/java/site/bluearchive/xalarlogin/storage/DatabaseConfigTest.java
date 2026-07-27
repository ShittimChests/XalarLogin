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
    @DisplayName("百分号编码会被拒绝——否则可以绕过整份黑名单")
    void percentEncodingIsRejected(@TempDir File dataFolder) {
        // Connector/J 解析 URL 时会做百分号解码，%61llowLoadLocalInfile 在校验侧看起来
        // 不命中黑名单，到了驱动那里却会还原成 allowLoadLocalInfile
        ConfigurationSection storage = mysqlConfig();
        storage.getConfigurationSection("mysql")
                .set("properties", "sslMode=PREFERRED&%61llowLoadLocalInfile=true");
        // 断言必须用校验专属的文案：驱动缺失的异常消息里现在带着完整 URL，
        // 而 URL 本身就含那个 %，拿 contains("%") 判的话删掉整个防护它照样绿
        assertTrue(messageOf(storage, dataFolder).contains("不允许出现 %"), "应指出问题出在百分号上");
    }

    @Test
    @DisplayName("会加载任意类的参数与 LOCAL INFILE 路径参数都在黑名单里")
    void classLoadingPropertiesAreRejected(@TempDir File dataFolder) {
        for (String pair : new String[]{
                "allowLoadLocalInfileInPath=/",
                "propertiesTransform=evil.Transform",
                "socketFactory=evil.Factory",
                "queryInterceptors=evil.Interceptor"}) {
            ConfigurationSection storage = mysqlConfig();
            storage.getConfigurationSection("mysql").set("properties", pair);
            String key = pair.split("=")[0].toLowerCase(java.util.Locale.ROOT);
            assertTrue(messageOf(storage, dataFolder).contains(key), pair + " 应被拒绝");
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
    @DisplayName("拼出来的 JDBC URL 形状正确，且超时默认值真的注入了")
    void mysqlUrlIsAssembledCorrectly() {
        assertEquals(
                "jdbc:mysql://db.example.com:3306/xalarlogin"
                        + "?connectTimeout=5000&socketTimeout=30000&sslMode=PREFERRED",
                Database.buildMysqlUrl("db.example.com", 3306, "xalarlogin", "sslMode=PREFERRED"));
    }

    @Test
    @DisplayName("create() 真的用上了拼装函数——超时默认值必须出现在生产路径拼出的 URL 里")
    void createActuallyUsesAssembledUrl(@TempDir File dataFolder) {
        // 上面那条只断言了 buildMysqlUrl 本身。光有它挡不住「重构时把 create() 里那次调用删掉、
        // 改回裸 properties」——那样两个孤立的单测照样全绿，而线上表现是数据库变成网络黑洞时
        // 一次读挂住 execute() 的锁、全服卡在 LOADING、连关服都关不掉。这条测的是 create()
        // 自己拼出来的串（驱动不在测试 classpath 上，异常消息里带着它）。
        String message = messageOf(mysqlConfig(), dataFolder);
        assertTrue(message.contains("jdbc:mysql://db.example.com:3306/xalarlogin"), message);
        assertTrue(message.contains("connectTimeout=5000"), "create() 必须补上连接超时默认值: " + message);
        assertTrue(message.contains("socketTimeout=30000"), "create() 必须补上读超时默认值: " + message);
    }

    @Test
    @DisplayName("IPv6 主机与自定义端口拼进 URL 的形状")
    void mysqlUrlWithIpv6AndBlankProperties() {
        assertEquals(
                "jdbc:mysql://[2001:db8::1]:13306/mc_auth?connectTimeout=5000&socketTimeout=30000",
                Database.buildMysqlUrl("[2001:db8::1]", 13306, "mc_auth", ""));
    }

    @Test
    @DisplayName("URL 进日志前会把凭据类参数抹掉")
    void secretsAreRedactedBeforeLogging() {
        // create() 失败时会把 URL 打进控制台，而管理员贴启动日志求助是常态
        String redacted = Database.redactSecrets(
                "jdbc:mysql://h:3306/db?sslMode=REQUIRED&trustCertificateKeyStorePassword=hunter2&x=1");
        assertTrue(redacted.contains("trustCertificateKeyStorePassword=***"), redacted);
        assertTrue(redacted.contains("sslMode=REQUIRED"), "无关参数不该被抹: " + redacted);
        assertTrue(redacted.contains("x=1"), redacted);
    }

    @Test
    @DisplayName("关掉数据库加密的几种写法都会被识别出来（警告而非拒绝）")
    void insecureTlsIsDetected() {
        assertEquals("allowPublicKeyRetrieval=true",
                Database.insecureTlsWarning("sslMode=DISABLED_TYPO&allowPublicKeyRetrieval=true"));
        assertEquals("useSSL=false", Database.insecureTlsWarning("useSSL=false"));
        assertEquals("sslMode=DISABLED", Database.insecureTlsWarning("sslMode=DISABLED"));
        assertEquals(null, Database.insecureTlsWarning("sslMode=PREFERRED&characterEncoding=utf8"));
        assertEquals(null, Database.insecureTlsWarning("allowPublicKeyRetrieval=false"));
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
