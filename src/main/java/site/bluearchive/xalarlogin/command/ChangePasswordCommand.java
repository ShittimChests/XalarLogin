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

/** /changepw <旧密码> <新密码> */
public final class ChangePasswordCommand implements CommandExecutor {

    private final XalarLoginPlugin plugin;

    public ChangePasswordCommand(XalarLoginPlugin plugin) {
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
        if (session == null || session.phase != Phase.LOGGED_IN) {
            player.sendMessage(plugin.message("must-login-first"));
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(plugin.message("changepw-usage"));
            return true;
        }
        int minLength = plugin.getConfig().getInt("min-password-length", 6);
        if (args[1].length() < minLength) {
            player.sendMessage(plugin.message("password-too-short", "{min}", String.valueOf(minLength)));
            return true;
        }

        UUID uuid = player.getUniqueId();
        String oldPassword = args[0];
        String newPassword = args[1];
        String storedHash = session.passwordHash;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (storedHash == null || !PasswordHasher.verify(oldPassword, storedHash)) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(plugin.message("changepw-wrong-old"));
                    }
                });
                return;
            }
            String newHash = PasswordHasher.hash(newPassword);
            boolean saved = true;
            try {
                plugin.database().updatePassword(uuid, newHash);
            } catch (SQLException e) {
                saved = false;
                plugin.getLogger().severe("修改玩家 " + player.getName() + " 密码失败: " + e.getMessage());
            }
            final boolean ok = saved;

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Session current = plugin.sessions().get(uuid);
                if (current == null || !player.isOnline()) {
                    return;
                }
                if (ok) {
                    current.passwordHash = newHash;
                    player.sendMessage(plugin.message("changepw-success"));
                } else {
                    player.sendMessage(plugin.message("db-error"));
                }
            });
        });
        return true;
    }
}
