package site.bluearchive.xalarlogin;

import java.sql.SQLException;
import java.util.Objects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import site.bluearchive.xalarlogin.command.AdminCommand;
import site.bluearchive.xalarlogin.command.ChangePasswordCommand;
import site.bluearchive.xalarlogin.command.LoginCommand;
import site.bluearchive.xalarlogin.command.RegisterCommand;
import site.bluearchive.xalarlogin.listener.RestrictionListener;
import site.bluearchive.xalarlogin.storage.Database;

public final class XalarLoginPlugin extends JavaPlugin {

    private Database database;
    private SessionManager sessions;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            database = new Database(getDataFolder());
        } catch (SQLException e) {
            getLogger().severe("无法初始化 SQLite 数据库: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        sessions = new SessionManager();

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
            try {
                database.close();
            } catch (SQLException e) {
                getLogger().warning("关闭数据库时出错: " + e.getMessage());
            }
        }
    }

    public Database database() {
        return database;
    }

    public SessionManager sessions() {
        return sessions;
    }

    /** 带前缀的聊天消息。replacements 形如 "{min}", "6" 成对出现。 */
    public Component message(String key, String... replacements) {
        String prefix = getConfig().getString("messages.prefix", "");
        return LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + rawMessage(key, replacements));
    }

    /** 无前缀消息，用于踢出界面。 */
    public Component bareMessage(String key, String... replacements) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(rawMessage(key, replacements));
    }

    private String rawMessage(String key, String... replacements) {
        String raw = getConfig().getString("messages." + key, "&c缺少消息配置: " + key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            raw = raw.replace(replacements[i], replacements[i + 1]);
        }
        return raw;
    }
}
