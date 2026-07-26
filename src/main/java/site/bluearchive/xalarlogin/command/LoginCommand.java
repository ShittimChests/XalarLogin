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

/** /a &lt;密码&gt; */
public final class LoginCommand implements CommandExecutor {

    private final XalarLoginPlugin plugin;

    public LoginCommand(XalarLoginPlugin plugin) {
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
            case NEED_REGISTER -> {
                player.sendMessage(plugin.message("not-registered"));
                return true;
            }
            case LOADING -> {
                player.sendMessage(plugin.message("loading"));
                return true;
            }
            case NEED_LOGIN -> {
            }
        }
        if (args.length != 1) {
            player.sendMessage(plugin.message("login-usage"));
            return true;
        }
        // 抢占放在参数校验之后：用法错误不该占住这把锁。
        // 没有它的话，连发 N 条 /a 会同时排进 N 次 PBKDF2，既能打满异步线程池，
        // 又能在失败计数（只在回调里递增）生效之前并发试出远超上限的密码。
        if (!session.busy.compareAndSet(false, true)) {
            player.sendMessage(plugin.message("processing"));
            return true;
        }

        String storedHash = session.passwordHash;
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String password = args[0];
        String ip = RestrictionListener.playerIp(player);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = storedHash != null && PasswordHasher.verify(password, storedHash);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                session.busy.set(false);
                if (plugin.sessions().get(uuid) != session || !player.isOnline()
                        || session.phase != Phase.NEED_LOGIN) {
                    return;
                }
                if (ok) {
                    plugin.sessions().markLoggedIn(player);
                    plugin.throttle().clear(name, ip);
                    player.sendMessage(plugin.message("login-success"));
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            plugin.database().updateLastLogin(uuid, ip);
                        } catch (SQLException e) {
                            plugin.getLogger().warning("更新玩家 " + name + " 登录时间失败: " + e.getMessage());
                        }
                    });
                    return;
                }
                handleWrongPassword(player, name, ip);
            });
        });
        return true;
    }

    /** 失败计数记在 LoginThrottle 而非 Session 上，这样踢出后重连也不会清零。 */
    private void handleWrongPassword(Player player, String name, String ip) {
        int maxAttempts = Math.max(1, plugin.getConfig().getInt("max-login-attempts", 3));
        long lockoutSeconds = Math.max(0, plugin.getConfig().getLong("lockout-seconds", 300));
        long now = System.currentTimeMillis();
        long retentionMillis = Math.max(lockoutSeconds, 60L) * 1000L;

        int attempts = plugin.throttle().recordFailure(name, ip, now, retentionMillis);
        if (attempts < maxAttempts) {
            player.sendMessage(plugin.message("wrong-password",
                    "{remaining}", String.valueOf(maxAttempts - attempts)));
            return;
        }
        if (lockoutSeconds > 0) {
            plugin.throttle().lock(name, ip, now, lockoutSeconds * 1000L);
            player.kick(plugin.bareMessage("kick-locked-out", "{seconds}", String.valueOf(lockoutSeconds)));
        } else {
            player.kick(plugin.bareMessage("kick-too-many-attempts"));
        }
    }
}
