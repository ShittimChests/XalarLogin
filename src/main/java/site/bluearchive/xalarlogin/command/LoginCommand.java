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

/** /a <密码> */
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

        String storedHash = session.passwordHash;
        UUID uuid = player.getUniqueId();
        String password = args[0];
        String ip = RestrictionListener.playerIp(player);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = storedHash != null && PasswordHasher.verify(password, storedHash);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Session current = plugin.sessions().get(uuid);
                if (current == null || !player.isOnline() || current.phase != Phase.NEED_LOGIN) {
                    return;
                }
                if (ok) {
                    plugin.sessions().markLoggedIn(uuid);
                    player.sendMessage(plugin.message("login-success"));
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            plugin.database().updateLastLogin(uuid, ip);
                        } catch (SQLException e) {
                            plugin.getLogger().warning("更新玩家 " + player.getName() + " 登录时间失败: " + e.getMessage());
                        }
                    });
                    return;
                }
                int maxAttempts = plugin.getConfig().getInt("max-login-attempts", 3);
                int attempts = current.failedAttempts.incrementAndGet();
                if (attempts >= maxAttempts) {
                    player.kick(plugin.bareMessage("kick-too-many-attempts"));
                } else {
                    player.sendMessage(plugin.message("wrong-password",
                            "{remaining}", String.valueOf(maxAttempts - attempts)));
                }
            });
        });
        return true;
    }
}
