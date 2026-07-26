package site.bluearchive.xalarlogin;

import java.io.File;
import java.sql.SQLException;
import java.util.Objects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("存储后端: " + database.backend());

        sessions = new SessionManager();
        throttle = new LoginThrottle();

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
        if (!spigotYml.isFile() || !YamlConfiguration.loadConfiguration(spigotYml).getBoolean("commands.log", true)) {
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
     * 关服时 Bukkit 只取消尚未开始的任务，已经在跑的异步任务会继续执行。
     * 直接关连接会让正在进行的注册/改密静默失败，这里给它们一点收尾时间。
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
        String prefix = getConfig().getString("messages.prefix", "");
        return render(prefix + rawMessage(key), replacements);
    }

    /** 无前缀消息，用于踢出界面。 */
    public Component bareMessage(String key, String... replacements) {
        return render(rawMessage(key), replacements);
    }

    private String rawMessage(String key) {
        return getConfig().getString("messages." + key, "&c缺少消息配置: " + key);
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
