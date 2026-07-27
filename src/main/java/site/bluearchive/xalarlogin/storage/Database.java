package site.bluearchive.xalarlogin.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 *
 * <p><b>这里的串行化只保证 SQL 的先后，保证不了主线程回调的先后。</b>
 * 单连接 + {@code synchronized} 能让 {@code /xalar passwd} 的 UPDATE 必然排在进服的 SELECT
 * 之后执行，但两个异步 worker 从各自的 SQL 返回、到调用 {@code runTask} 之间没有任何同步，
 * 调度器按 {@code runTask} 的调用顺序排队。所以「进服回调把查库那一刻的旧哈希写回会话，
 * 覆盖掉管理员刚写进去的新哈希」这个窗口是真实存在的，靠串行化关不掉——真正关掉它的是
 * {@code Session.passwordGeneration}：进服回调发现代号变了就放弃写回。新增「先读后写会话」
 * 的路径时请沿用这个代号，别再假设回调顺序。
 */
public final class Database implements AutoCloseable {

    /** 账号数据快照。lastIp 为 null 表示从未记录过登录 IP。 */
    public record Account(String passwordHash, String lastIp) {
    }

    /** 表名会直接拼进 SQL，只允许标识符字符 */
    private static final Pattern SAFE_TABLE = Pattern.compile("[A-Za-z0-9_]{1,64}");
    /** 主机名 / IPv4，或方括号包起来的 IPv6 */
    private static final Pattern SAFE_HOST =
            Pattern.compile("[A-Za-z0-9._-]{1,255}|\\[[0-9A-Fa-f:.]{2,45}]");
    /** 库名拼在 URL 的路径段上，允许 MySQL 标识符里实际会用到的字符 */
    private static final Pattern SAFE_DATABASE = Pattern.compile("[A-Za-z0-9_$-]{1,64}");
    private static final String DEFAULT_TABLE = "accounts";

    /**
     * 禁止出现在 storage.mysql.properties 里的 Connector/J 参数。
     *
     * <p>前四个会把「连到恶意或被劫持的 MySQL」升级成服务端上的任意文件读取或反序列化执行，
     * 而这个插件一个都用不到。{@code databaseTerm} 则会让 {@code getCatalog()} 返回 null，
     * 使 {@link #migrateAddLastIp} 的列检测永远判为「缺列」，启动时 ALTER 撞重复列直接停用插件。
     *
     * <p>光过滤 properties 是不够的：{@code host} 与 {@code database} 也是原样拼进同一个 URL 的，
     * 不校验的话 {@code database: 'xalarlogin?allowLoadLocalInfile=true'} 就能绕过这份名单。
     * 所以三个字段都要卡，见 {@link #SAFE_HOST} 与 {@link #SAFE_DATABASE}。
     */
    private static final Set<String> BANNED_PROPERTIES = Set.of(
            // LOCAL INFILE 与反序列化：连到恶意/被劫持的 MySQL 时能读服务端本地文件或执行代码。
            // autoDeserialize 在 Connector/J 9.x 已被移除，留着是为了兼容更老的驱动
            "autodeserialize", "allowloadlocalinfile", "allowloadlocalinfileinpath",
            "allowurlinlocalinfile", "allowmultiqueries",
            // 会让 getCatalog() 返回 null，从而搞坏 migrateAddLastIp 的列检测
            "databaseterm",
            // 下面这些都会让 Connector/J 按名字去加载并实例化任意类。名字逐个对照过
            // Connector/J 9.2.0 的 PropertyKey 枚举——写错名字比漏写更糟，那会让本来合法的
            // 配置在升级后直接把插件停掉。ha.loadBalanceStrategy 有两个别名，都要列
            "propertiestransform", "socketfactory", "queryinterceptors",
            "connectionlifecycleinterceptors", "exceptioninterceptors", "authenticationplugins",
            "clientinfoprovider", "profilereventhandler", "logger",
            "serverconfigcachefactory", "parseinfocachefactory", "queryinfocachefactory",
            "keymanagerfactoryprovider", "trustmanagerfactoryprovider",
            "ha.loadbalancestrategy", "haloadbalancestrategy", "loadbalanceexceptionchecker",
            // 凭据有专门的配置项。写进 properties 会被拼进 JDBC URL，
            // 而这条 URL 会出现在「找不到驱动」的错误日志里
            "user", "password");

    /**
     * 不禁止、但启动时要出声的参数。
     *
     * <p>这个组合在 MySQL 8 默认的 caching_sha2_password 下允许中间人塞入自己的 RSA 公钥并
     * 还原出数据库密码——config.yml 的注释一直在劝阻它，却只有劝阻没有提醒。
     * 不直接拒绝是因为本机非 TLS 的 MySQL 确实可能需要 allowPublicKeyRetrieval。
     */
    private static final Set<String> INSECURE_TLS_PROPERTIES = Set.of(
            "allowpublickeyretrieval", "usessl", "sslmode");

    /**
     * properties 里不允许出现百分号。
     *
     * <p>光有黑名单不够：Connector/J 解析 URL 时会对参数做百分号解码，
     * {@code %61llowLoadLocalInfile=true} 在 {@link #propertyKeys} 看来是一个叫
     * {@code %61llowloadlocalinfile} 的参数，不命中名单，却会被驱动还原成真正的那一个。
     *
     * <p>只禁这一个字符而不是整体白名单：百分号是唯一的编码入口，禁掉它编码就不成立了；
     * 而 {@code sessionVariables=sql_mode='...'} 之类带引号、空格的合法参数还能继续用。
     */
    private static final char PROPERTY_ENCODING_CHAR = '%';

    /**
     * 没显式配置时补上的连接/读超时。
     *
     * <p>Connector/J 的 socketTimeout 默认不限时，网络黑洞时一次读能挂到 TCP 自己放弃为止。
     * 而 {@link #execute} 是 {@code synchronized} 的，一个挂住的读会一直占着这把锁：登录全卡在
     * LOADING，关服时 {@code close()} 也得排在它后面，{@code XalarLoginPlugin} 那 2 秒收尾上限
     * 就形同虚设。补默认值而不是只写进 config.yml，是为了让升级上来的老配置也能受益。
     */
    private static final String DEFAULT_CONNECT_TIMEOUT = "connectTimeout=5000";
    private static final String DEFAULT_SOCKET_TIMEOUT = "socketTimeout=30000";

    @FunctionalInterface
    private interface SqlAction<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Backend backend;
    /** 不安全的 TLS 配置说明，没有则为 null。onEnable 会把它打成启动警告 */
    private final String tlsWarning;
    private final String url;
    private final String user;
    private final String password;
    private final String table;

    private Connection connection;
    /** {@link #close()} 之后为 true，{@link #execute} 据此拒绝晚到的调用而不是重开连接 */
    private boolean closed;

    private Database(Backend backend, String url, String user, String password, String table,
                     String tlsWarning) throws SQLException {
        this.backend = backend;
        this.tlsWarning = tlsWarning;
        this.url = url;
        this.user = user;
        this.password = password;
        this.table = table;
        try {
            Class.forName(backend.driverClass());
        } catch (ClassNotFoundException e) {
            // 把 URL 一并报出来：管理员排障时能一眼看到实际用的连接串（密码是单独传的，不在里面），
            // 同时也让 DatabaseConfigTest 能对 create() 这条生产路径断言——URL 拼装的回归
            // （比如漏掉 withTimeoutDefaults）只在真实网络故障时才有症状，本地无论如何都测不出来
            throw new SQLException("找不到 " + backend + " 的 JDBC 驱动（" + backend.driverClass()
                    + "），请确认运行在 Paper 服务端上；连接串为 " + redactSecrets(url), e);
        }
        reconnect();
        try {
            initSchema();
        } catch (SQLException | RuntimeException e) {
            // 建表/迁移失败会让 onEnable 停用插件，但连接已经建立并通过认证了。
            // 不关的话它会一直挂在数据库的连接表里——管理员每 /reload 一次重试配置就多泄漏一条
            closeQuietly();
            throw e;
        }
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
            return new Database(backend, "jdbc:sqlite:" + dbFile.getAbsolutePath(),
                    null, null, DEFAULT_TABLE, null);
        }

        ConfigurationSection mysql = storage.getConfigurationSection("mysql");
        if (mysql == null) {
            throw new SQLException("storage.type 是 mysql，但 config.yml 里没有 storage.mysql 段");
        }
        String database = mysql.getString("database", "");
        if (database.isBlank()) {
            throw new SQLException("storage.mysql.database 不能为空");
        }
        if (!SAFE_DATABASE.matcher(database).matches()) {
            throw new SQLException("storage.mysql.database 只能包含字母、数字、下划线、$ 和减号，"
                    + "当前配置为: " + database);
        }
        String host = mysql.getString("host", "localhost");
        if (!SAFE_HOST.matcher(host).matches()) {
            throw new SQLException("storage.mysql.host 不是合法的主机名或 IP（IPv6 请用方括号包起来），"
                    + "当前配置为: " + host);
        }
        int port = mysql.getInt("port", 3306);
        if (port < 1 || port > 65535) {
            throw new SQLException("storage.mysql.port 必须在 1~65535 之间，当前配置为: " + port);
        }
        String table = mysql.getString("table", DEFAULT_TABLE);
        if (!SAFE_TABLE.matcher(table).matches()) {
            throw new SQLException("storage.mysql.table 只能包含字母、数字和下划线，当前配置为: " + table);
        }
        String properties = mysql.getString("properties", "");
        checkProperties(properties);
        String url = buildMysqlUrl(host, port, database, properties);
        return new Database(backend, url, mysql.getString("user", ""), mysql.getString("password", ""),
                table, insecureTlsWarning(properties));
    }

    /**
     * 拼出 MySQL 的 JDBC URL。三个字段都已经过白名单校验，properties 已过黑名单。
     *
     * <p>抽成纯函数是为了能直接断言拼出来的字符串：超时默认值有没有真的注入、分隔符对不对，
     * 这些只在真实数据库上才看得出问题，而补超时默认值的<b>唯一</b>理由就是防止一次挂住的读
     * 占着 {@link #execute} 的锁让全服卡在登录上。漏掉这一步在本地是完全没有症状的。
     */
    static String buildMysqlUrl(String host, int port, String database, String properties) {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?" + withTimeoutDefaults(properties);
    }

    /**
     * 把 URL 里任何形似凭据的参数值抹掉再拿去打日志。
     *
     * <p>{@code user}/{@code password} 已经进了黑名单，但 Connector/J 还有
     * {@code trustCertificateKeyStorePassword} 之类一串带 password 的参数，与其逐个列举，
     * 不如在输出侧统一按名字兜一层。
     */
    static String redactSecrets(String url) {
        return url.replaceAll("(?i)([?&][^=&]*(?:password|user)[^=&]*=)[^&]*", "$1***");
    }

    /** 检查出用了不安全的 TLS 配置就返回说明，否则返回 null。包级可见以便测试。 */
    static String insecureTlsWarning(String properties) {
        for (String pair : properties.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = kv[0].trim().toLowerCase(Locale.ROOT);
            String value = kv.length > 1 ? kv[1].trim().toLowerCase(Locale.ROOT) : "";
            if (!INSECURE_TLS_PROPERTIES.contains(key)) {
                continue;
            }
            if (key.equals("allowpublickeyretrieval") && value.equals("true")
                    || key.equals("usessl") && value.equals("false")
                    || key.equals("sslmode") && value.equals("disabled")) {
                return pair.trim();
            }
        }
        return null;
    }

    /** properties 是原样拼进 JDBC URL 的，先把已知会造成危害的参数挡掉。 */
    private static void checkProperties(String properties) throws SQLException {
        if (properties.indexOf(PROPERTY_ENCODING_CHAR) >= 0) {
            throw new SQLException("storage.mysql.properties 里不允许出现 % —— 驱动会对它做百分号解码，"
                    + "可以借此绕过下面的参数黑名单，当前配置为: " + properties);
        }
        for (String key : propertyKeys(properties)) {
            if (BANNED_PROPERTIES.contains(key)) {
                throw new SQLException("storage.mysql.properties 里不允许出现 " + key
                        + "，它会让插件在连到恶意数据库时受到攻击，或破坏建表迁移");
            }
        }
    }

    /** @return properties 里出现过的参数名，全部小写 */
    private static Set<String> propertyKeys(String properties) {
        Set<String> keys = new HashSet<>();
        for (String pair : properties.split("&")) {
            String key = pair.split("=", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** 管理员没显式配超时时补上默认值，理由见 {@link #DEFAULT_SOCKET_TIMEOUT}。包级可见以便测试。 */
    static String withTimeoutDefaults(String properties) {
        Set<String> present = propertyKeys(properties);
        List<String> parts = new ArrayList<>(3);
        if (!present.contains("connecttimeout")) {
            parts.add(DEFAULT_CONNECT_TIMEOUT);
        }
        if (!present.contains("sockettimeout")) {
            parts.add(DEFAULT_SOCKET_TIMEOUT);
        }
        if (!properties.isBlank()) {
            parts.add(properties);
        }
        return String.join("&", parts);
    }

    public Backend backend() {
        return backend;
    }

    /** @return 不安全 TLS 配置的说明，配置没问题时返回 null */
    public String tlsWarning() {
        return tlsWarning;
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
     *
     * <p><b>action 必须幂等</b>：重试跑的是整个 action，而语句有可能是「服务端已经执行成功、
     * 只是返回途中连接断了」。现有调用都满足——SELECT 无副作用，INSERT IGNORE 与三条按主键
     * 的 UPDATE 重跑一遍结果相同。两个已知的边角：{@link #deleteByName} 重跑会返回 0，管理员
     * 会看到「未找到玩家」而记录其实已删；{@link #migrateAddLastIp} 的 ALTER 重跑会撞重复列。
     * 两者都要求第一次已经到达服务端才会发生，新增非幂等语句前请先想清楚这一点。
     */
    private synchronized <T> T execute(SqlAction<T> action) throws SQLException {
        // 关服时 awaitPendingTasks 只等 2 秒，之后 close() 就跑了；晚到的调用不能因为
        // connection == null 就重新开一条——那条连接没有任何人会再关，/reload 反复触发
        // 就是每次泄漏一条，而且它会和新实例的 Database 同时写同一个库
        if (closed) {
            throw new SQLException("数据库连接已关闭（插件正在停用）");
        }
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
            DatabaseMetaData meta = conn.getMetaData();
            // getColumns 的表名和列名都是 LIKE pattern，'_' 是单字符通配符，
            // 而表名允许下划线、列名本身就叫 last_ip，不转义会匹配到别的表/列上去
            String escape = meta.getSearchStringEscape();
            // catalog 这个参数**不要**转义：JDBC 规范里它是精确名称而不是 LIKE 模式，
            // 只有 schemaPattern / tableNamePattern / columnNamePattern 才是模式。
            // 转义过的库名（mc\_auth）在 Connector/J 的等值比较下一条都匹配不到，
            // 于是每次启动都判为「缺列」，第二次启动 ALTER 就撞 Duplicate column 直接停用插件。
            // 已在真实 MySQL 8.0.46 + Connector/J 9.2.0 上验证过这两种写法的差别
            try (ResultSet rs = meta.getColumns(conn.getCatalog(), null,
                    escapePattern(table, escape), escapePattern("last_ip", escape))) {
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

    /** 给 {@code getColumns} 之类的 pattern 参数转义 {@code _} 和 {@code %}。 */
    private static String escapePattern(String value, String escape) {
        if (escape == null || escape.isEmpty()) {
            return value;
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            if (c == '_' || c == '%' || escape.indexOf(c) >= 0) {
                escaped.append(escape);
            }
            escaped.append(c);
        }
        return escaped.toString();
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

    /**
     * 改密码同时清空 last_ip：密码变化后下次进服必须重新输密码，免密会话作废。
     *
     * @return 实际改动的记录数；0 表示这个 UUID 已经没有账号了（多服共用一套库时，
     *         另一台服务器可能刚把它删掉），调用方必须把 0 当失败处理
     */
    public int updatePassword(UUID uuid, String passwordHash) throws SQLException {
        return execute(conn -> {
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

    /**
     * 记录成功登录的时间与来源 IP（IP 用于同 IP 免密登录）。
     *
     * <p>带上 {@code expectedHash} 条件：这条 UPDATE 是登录成功后异步补写的，不受 session.busy
     * 保护，可能在管理员 {@code /xalar passwd} 清空 last_ip <b>之后</b>才落库，把刚作废的免密
     * 凭据又写回去。加上「密码哈希还是我认证时那个」的条件，改过密码的情况下就匹配不到行，
     * last_ip 保持清空状态。
     *
     * @return 实际改动的行数；0 表示这期间密码被改过或账号被删了，调用方无需处理
     */
    public int updateLastLogin(UUID uuid, String ip, String expectedHash) throws SQLException {
        return execute(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE " + table
                    + " SET last_login = ?, last_ip = ? WHERE uuid = ? AND password_hash = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, ip);
                ps.setString(3, uuid.toString());
                ps.setString(4, expectedHash);
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
        closed = true;
        if (connection != null) {
            Connection open = connection;
            connection = null;
            open.close();
        }
    }
}
