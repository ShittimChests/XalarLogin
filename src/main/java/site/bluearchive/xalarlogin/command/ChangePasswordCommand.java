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

/** /changepw &lt;旧密码&gt; &lt;新密码&gt; */
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
        // 没有这把锁的话，连发两条改密命令会各自拿同一份旧哈希校验通过、各自写库，
        // 最后数据库留下后写入的那个、会话缓存留下后回调的那个，两者可能对不上。
        if (!session.busy.compareAndSet(false, true)) {
            player.sendMessage(plugin.message("processing"));
            return true;
        }

        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String oldPassword = args[0];
        String newPassword = args[1];
        String storedHash = session.passwordHash;
        int iterations = plugin.hashIterations();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (storedHash == null || !PasswordHasher.verify(oldPassword, storedHash)) {
                plugin.runOnMain(() -> {
                    session.busy.set(false);
                    // 和下面的成功分支一样比对会话实例：玩家退服重连后 map 里已是新会话，
                    // 给它发上一次连接的「旧密码错误」是误导
                    if (plugin.sessions().get(uuid) != session || !player.isOnline()) {
                        return;
                    }
                    player.sendMessage(plugin.message("changepw-wrong-old"));
                });
                return;
            }
            String newHash = PasswordHasher.hash(newPassword, iterations);
            // 改动行数不能丢：多个服务端共用一套 MySQL 时，这条 UPDATE 可能一行都没匹配上
            // （另一台服务器刚把这个账号删了）。当成成功回报的话，玩家会看到「密码修改成功」、
            // 会话缓存也换成新哈希，直到下次进服才发现账号根本不存在
            int updated = -1;
            try {
                updated = plugin.database().updatePassword(uuid, newHash);
            } catch (SQLException e) {
                plugin.getLogger().severe("修改玩家 " + name + " 密码失败: " + e.getMessage());
            }
            final boolean ok = updated > 0;

            plugin.runOnMain(() -> {
                session.busy.set(false);
                if (plugin.sessions().get(uuid) != session || !player.isOnline()) {
                    return;
                }
                if (ok) {
                    session.setPasswordHash(newHash);
                    player.sendMessage(plugin.message("changepw-success"));
                } else {
                    player.sendMessage(plugin.message("db-error"));
                }
            });
        });
        return true;
    }
}
