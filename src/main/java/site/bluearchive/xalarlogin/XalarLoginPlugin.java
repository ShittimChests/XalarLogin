package site.bluearchive.xalarlogin;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitWorker;

import site.bluearchive.xalarlogin.command.AdminCommand;
import site.bluearchive.xalarlogin.command.ChangePasswordCommand;
import site.bluearchive.xalarlogin.command.LoginCommand;
import site.bluearchive.xalarlogin.command.RegisterCommand;
import site.bluearchive.xalarlogin.listener.RestrictionListener;
import site.bluearchive.xalarlogin.storage.Database;

public final class XalarLoginPlugin extends JavaPlugin {

    /** 关服时等待未完成的数据库任务收尾的上限 */
    private static final long SHUTDOWN_DRAIN_MILLIS = 2_000L;

    private Database database;
    private SessionManager sessions;
    private LoginThrottle throttle;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        warnIfCommandLoggingEnabled();

        try {
            database = Database.create(getConfig().getConfigurationSection("storage"), getDataFolder());
        } catch (SQLException e) {
            // 配置写错时宁可停在这里也不要静默退回 SQLite——那会让本该连 MySQL 的服务器
            // 悄悄建一个本地空库，看起来「所有人都没注册过」
            getLogger().severe("无法初始化数据库: " + e.getMessage());
            getLogger().severe("请检查 config.yml 的 storage 段；插件已停用。");
            // 这里不需要踢人：监听器还没注册、sessions 还是 null，本实例没有冻结任何人。
            // /reload 场景下冻结中的玩家由上一个实例的 onDisable 负责断开
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("存储后端: " + database.backend());
        if (database.tlsWarning() != null) {
            getLogger().warning("=========================== 安全警告 ===========================");
            getLogger().warning(" storage.mysql.properties 里的 " + database.tlsWarning()
                    + " 关掉了到数据库的加密。");
            getLogger().warning(" MySQL 8 默认的 caching_sha2_password 下，这允许中间人塞入自己的");
            getLogger().warning(" RSA 公钥，从而还原出你的数据库密码。数据库与服务端不在同一台机器");
            getLogger().warning(" 上时尤其危险。推荐改回 sslMode=PREFERRED。");
            getLogger().warning("===============================================================");
        }

        sessions = new SessionManager();
        throttle = new LoginThrottle();
        warnIfIpSessionEnabled();

        RestrictionListener listener = new RestrictionListener(this);
        getServer().getPluginManager().registerEvents(listener, this);

        Objects.requireNonNull(getCommand("reg")).setExecutor(new RegisterCommand(this));
        Objects.requireNonNull(getCommand("a")).setExecutor(new LoginCommand(this));
        Objects.requireNonNull(getCommand("changepw")).setExecutor(new ChangePasswordCommand(this));
        Objects.requireNonNull(getCommand("xalar")).setExecutor(new AdminCommand(this));

        // 兼容 /reload：为已在线玩家重建会话
        for (Player player : getServer().getOnlinePlayers()) {
            listener.initializePlayer(player);
        }

        getLogger().info("XalarLogin 已启用");
    }

    @Override
    public void onDisable() {
        // 先踢再清：停用之后所有冻结 handler 都会被摘掉，留在服务器里的未认证玩家会当场
        // 恢复行动能力。离线模式下那可能是一个正顶着别人名字的人，所以停用时宁可把他断开
        kickUnauthenticated();
        if (sessions != null) {
            sessions.clear();
        }
        if (database != null) {
            awaitPendingTasks();
            try {
                database.close();
            } catch (SQLException e) {
                getLogger().warning("关闭数据库时出错: " + e.getMessage());
            }
        }
    }

    /**
     * Paper 在构造 PlayerCommandPreprocessEvent <b>之前</b> 就会把命令原文写进日志
     * （ServerGamePacketListenerImpl 里 SpigotConfig.logCommands 的判断早于事件触发），
     * 所以插件无论怎么取消事件都拦不住密码明文落盘，只能提醒管理员改服务端配置。
     * 受影响的是全部四条带密码的命令：/reg、/a、/changepw、/xalar passwd。
     */
    private void warnIfCommandLoggingEnabled() {
        // getDataFolder() 是 plugins/XalarLogin，而且通常是相对路径，
        // 必须先转成绝对路径再往上走两级，否则第二级 getParentFile() 会直接返回 null
        File pluginsFolder = getDataFolder().getAbsoluteFile().getParentFile();
        File serverRoot = pluginsFolder == null ? null : pluginsFolder.getParentFile();
        if (serverRoot == null) {
            return;
        }
        File spigotYml = new File(serverRoot, "spigot.yml");
        if (!spigotYml.isFile()) {
            // 用 --spigot-settings 换过路径时会走到这里。这条警告是「密码明文落盘」唯一的缓解
            // 手段，静默跳过等于让管理员以为没问题，所以至少留个痕迹
            getLogger().info("未找到 " + spigotYml.getPath()
                    + "，跳过命令日志检查；请自行确认 spigot.yml 里 commands.log 为 false");
            return;
        }
        if (!YamlConfiguration.loadConfiguration(spigotYml).getBoolean("commands.log", true)) {
            return;
        }
        getLogger().warning("=========================== 安全警告 ===========================");
        getLogger().warning(" spigot.yml 里 commands.log 为 true，服务端会把玩家执行的");
        getLogger().warning(" 命令原文写入 logs/latest.log —— 这意味着 /reg、/a、/changepw");
        getLogger().warning(" 和 /xalar passwd 的密码都会以明文落盘。");
        getLogger().warning(" 该日志早于插件事件触发，插件无法拦截。");
        getLogger().warning(" 请把 spigot.yml 的 commands.log 改为 false 并重启服务器。");
        getLogger().warning("===============================================================");
    }

    /**
     * 免密登录只比对出口 IP，而离线模式下谁都能用别人的名字进服，所以它默认是关的。
     * 但 {@code saveDefaultConfig()} 只在文件不存在时写入——从老版本升级上来的服务器，
     * config.yml 里这一项仍然是当初的 {@code true}，默认值的改动对他们没有任何作用。
     * 说明书里写着「默认关闭」，不出声的话服主会以为风险已经消除。
     */
    private void warnIfIpSessionEnabled() {
        if (!getConfig().getBoolean("ip-session-enabled", false)) {
            return;
        }
        getLogger().warning("=========================== 安全警告 ===========================");
        getLogger().warning(" config.yml 里 ip-session-enabled 为 true（同 IP 免密登录已开启）。");
        getLogger().warning(" 离线模式下任何人都能用别人的名字进服，而免密只比对出口 IP —— 宿舍、");
        getLogger().warning(" 家庭 NAT、运营商 CGNAT、共用 VPN 出口后面的人因此可以互相顶号，");
        getLogger().warning(" 不需要密码，也完全绕过登录失败锁定。");
        getLogger().warning(" 该项现在的推荐值是 false；从旧版本升级的配置需要手动改。");
        getLogger().warning("===============================================================");
    }

    /**
     * 断开所有还没通过认证的玩家。
     *
     * <p>停用插件会摘掉全部冻结 handler 并取消超时任务，留在服务器里的未认证玩家会当场
     * 恢复行动能力——离线模式下那可能是一个正顶着管理员名字的人。配置写错时「宁可停用也不
     * 静默退回 SQLite」的前提是停用等于把门关上，而不是把门打开。
     *
     * <p>判定用的是「有会话且还没登录」，而不是「没登录」：只有本实例给他建过会话的玩家才是
     * 被我们冻结的人。用后者的话，{@code onEnable} 在建完 {@code sessions} 之后、给在线玩家
     * 建会话之前抛异常（例如 plugin.yml 少了一条命令导致 {@code requireNonNull} 抛 NPE），
     * 停用时会把全服所有人一起踢下线——他们根本没被我们冻结过。
     */
    private void kickUnauthenticated() {
        if (sessions == null) {
            return;
        }
        // 遍历副本：getOnlinePlayers() 返回的是玩家列表的活视图，kick 会把人从底层列表里摘掉，
        // 边迭代边踢可能抛 ConcurrentModificationException
        for (Player player : List.copyOf(getServer().getOnlinePlayers())) {
            if (sessions.isFrozen(player.getUniqueId())) {
                player.kick(bareMessage("kick-plugin-disabled"));
            }
        }
    }

    /**
     * 回到主线程执行一段收尾逻辑；插件已经停用时直接丢弃。
     *
     * <p>{@link #awaitPendingTasks()} 等的就是那些还在跑的异步任务，而它们收尾时都要
     * {@code runTask} 回主线程——此时 {@code isEnabled()} 已经是 false，调度器会抛
     * {@code IllegalPluginAccessException}，在控制台留下一条像是数据库出错的异常栈。
     * 数据库写入在回调之前就完成了，丢掉的只是发消息、改会话这些关服后没有意义的动作。
     */
    public void runOnMain(Runnable task) {
        if (!isEnabled()) {
            return;
        }
        try {
            getServer().getScheduler().runTask(this, task);
        } catch (IllegalPluginAccessException e) {
            // 上面的检查与这次调用之间仍有竞态窗口，落到这里同样只是丢弃收尾动作
        }
    }

    /**
     * 关服时 Bukkit 只取消尚未开始的任务，已经在跑的异步任务会继续执行。
     * 直接关连接会让正在进行的注册/改密静默失败，这里给它们一点收尾时间。
     *
     * <p>注意这个上限只约束轮询循环，不约束整个关服路径：超时后紧接着的
     * {@code database.close()} 是 {@code synchronized} 的，会排在卡住的那个查询后面。
     * 真正让它有界的是 {@code Database} 给 MySQL 补的 socketTimeout 默认值。
     */
    private void awaitPendingTasks() {
        long deadline = System.currentTimeMillis() + SHUTDOWN_DRAIN_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            boolean running = getServer().getScheduler().getActiveWorkers().stream()
                    .map(BukkitWorker::getOwner)
                    .anyMatch(owner -> owner == this);
            if (!running) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        getLogger().warning("关服时仍有未完成的数据库任务，已强制关闭连接");
    }

    public Database database() {
        return database;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public LoginThrottle throttle() {
        return throttle;
    }

    /** 密码哈希迭代次数，越界的配置会被钳进 PasswordHasher 接受的区间。 */
    public int hashIterations() {
        return PasswordHasher.clampIterations(
                getConfig().getInt("password-hash-iterations", PasswordHasher.DEFAULT_ITERATIONS));
    }

    /** 带前缀的聊天消息。replacements 形如 "{min}", "6" 成对出现。 */
    public Component message(String key, String... replacements) {
        // 和 rawMessage 一样必须用单参数重载，否则老 config.yml 里没写 messages.prefix 时
        // 拿到的是空串而不是 jar 里的内置前缀，玩家看到的提示会和普通聊天混在一起
        String prefix = getConfig().getString("messages.prefix");
        return render((prefix == null ? "" : prefix) + rawMessage(key), replacements);
    }

    /** 无前缀消息，用于踢出界面。 */
    public Component bareMessage(String key, String... replacements) {
        return render(rawMessage(key), replacements);
    }

    /**
     * 必须用单参数的 {@code getString}：带默认值的重载走的是 {@code MemorySection.get(path, def)}，
     * 它直接返回传入的默认值，<b>不会</b>去查 {@code JavaPlugin.reloadConfig()} 注册的 jar 内默认配置。
     * 用那个重载的话，升级上来的老 config.yml 里每一个新增的 messages key 都会显示成
     * 「缺少消息配置」而不是内置文案——本插件说明书里承诺的「缺少的项自动用内置默认值」就不成立了。
     */
    private String rawMessage(String key) {
        String value = getConfig().getString("messages." + key);
        return value == null ? "&c缺少消息配置: " + key : value;
    }

    /**
     * 先把模板反序列化成 Component 再替换占位符，替换值因此是纯文本。
     * 如果反过来先拼字符串，玩家名之类的外部输入里出现 &amp; 就会被当成颜色码解析。
     */
    private Component render(String template, String... replacements) {
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(template);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            String placeholder = replacements[i];
            String value = replacements[i + 1];
            component = component.replaceText(builder ->
                    builder.matchLiteral(placeholder).replacement(Component.text(value)));
        }
        return component;
    }
}
