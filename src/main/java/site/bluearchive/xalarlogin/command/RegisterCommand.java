package site.bluearchive.xalarlogin.command;

import java.sql.SQLException;
import java.util.UUID;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import site.bluearchive.xalarlogin.PasswordHasher;
import site.bluearchive.xalarlogin.SessionManager.Phase;
import site.bluearchive.xalarlogin.SessionManager.Session;
import site.bluearchive.xalarlogin.XalarLoginPlugin;
import site.bluearchive.xalarlogin.listener.RestrictionListener;
import site.bluearchive.xalarlogin.storage.Database;

/** /reg &lt;密码&gt; &lt;重复密码&gt; */
public final class RegisterCommand implements CommandExecutor {

    private final XalarLoginPlugin plugin;

    public RegisterCommand(XalarLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("player-only"));
            return true;
        }
        Session session = plugin.sessions().get(player.getUniqueId());
        if (session == null) {
            return true;
        }
        switch (session.phase) {
            case LOGGED_IN -> {
                player.sendMessage(plugin.message("already-logged-in"));
                return true;
            }
            case NEED_LOGIN -> {
                player.sendMessage(plugin.message("already-registered"));
                return true;
            }
            case LOADING -> {
                player.sendMessage(plugin.message("loading"));
                return true;
            }
            case NEED_REGISTER -> {
            }
        }
        if (args.length != 2) {
            player.sendMessage(plugin.message("register-usage"));
            return true;
        }
        if (!args[0].equals(args[1])) {
            player.sendMessage(plugin.message("password-mismatch"));
            return true;
        }
        int minLength = plugin.getConfig().getInt("min-password-length", 6);
        if (args[0].length() < minLength) {
            player.sendMessage(plugin.message("password-too-short", "{min}", String.valueOf(minLength)));
            return true;
        }
        if (!session.busy.compareAndSet(false, true)) {
            player.sendMessage(plugin.message("processing"));
            return true;
        }

        UUID uuid = player.getUniqueId();
        String password = args[0];
        String name = player.getName();
        String ip = RestrictionListener.playerIp(player);
        int iterations = plugin.hashIterations();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String hash = PasswordHasher.hash(password, iterations);
            boolean inserted = false;
            Database.Account existing = null;
            SQLException failure = null;
            try {
                inserted = plugin.database().register(uuid, name, hash, ip);
                if (!inserted) {
                    // 这次注册的异步处理途中玩家退服重连过，账号其实已经落库了。
                    // 把真实哈希读回来，让玩家直接去 /a，而不是卡在「注册失败」里出不来。
                    existing = plugin.database().findAccount(uuid);
                }
            } catch (SQLException e) {
                failure = e;
                plugin.getLogger().severe("注册玩家 " + name + " 失败: " + e.getMessage());
            }
            final boolean created = inserted;
            final Database.Account account = existing;
            final SQLException error = failure;

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                session.busy.set(false);
                if (plugin.sessions().get(uuid) != session || !player.isOnline()
                        || session.phase != Phase.NEED_REGISTER) {
                    return;
                }
                if (error != null) {
                    player.sendMessage(plugin.message("db-error"));
                    return;
                }
                if (!created) {
                    if (account == null) {
                        // 插入被忽略却又查不到记录，说明这中间账号被删了；留在 NEED_REGISTER 让玩家重试
                        player.sendMessage(plugin.message("db-error"));
                        return;
                    }
                    session.passwordHash = account.passwordHash();
                    session.phase = Phase.NEED_LOGIN;
                    player.sendMessage(plugin.message("already-registered"));
                    return;
                }
                session.passwordHash = hash;
                plugin.sessions().markLoggedIn(player);
                plugin.throttle().clear(name, ip);
                player.sendMessage(plugin.message("register-success"));
            });
        });
        return true;
    }
}
