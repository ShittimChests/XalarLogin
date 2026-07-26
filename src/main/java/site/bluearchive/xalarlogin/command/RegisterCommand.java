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

/** /reg <密码> <重复密码> */
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

        // 防止重复提交：处理期间置回 LOADING
        session.phase = Phase.LOADING;
        UUID uuid = player.getUniqueId();
        String password = args[0];
        String name = player.getName();
        String ip = RestrictionListener.playerIp(player);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String hash = PasswordHasher.hash(password);
            SQLException failure = null;
            try {
                plugin.database().register(uuid, name, hash, ip);
            } catch (SQLException e) {
                failure = e;
                plugin.getLogger().severe("注册玩家 " + name + " 失败: " + e.getMessage());
            }
            final SQLException error = failure;

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Session current = plugin.sessions().get(uuid);
                if (current == null || !player.isOnline()) {
                    return;
                }
                if (error != null) {
                    current.phase = Phase.NEED_REGISTER;
                    player.sendMessage(plugin.message("db-error"));
                    return;
                }
                current.passwordHash = hash;
                plugin.sessions().markLoggedIn(uuid);
                player.sendMessage(plugin.message("register-success"));
            });
        });
        return true;
    }
}
