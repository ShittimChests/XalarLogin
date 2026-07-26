package site.bluearchive.xalarlogin.command;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import site.bluearchive.xalarlogin.PasswordHasher;
import site.bluearchive.xalarlogin.SessionManager.Session;
import site.bluearchive.xalarlogin.XalarLoginPlugin;

/**
 * 管理命令，权限 xalarlogin.admin，控制台可用：
 * <ul>
 *   <li>{@code /xalar unregister <玩家>} —— 删号
 *   <li>{@code /xalar passwd <玩家> <新密码>} —— 直接改密码
 * </ul>
 */
public final class AdminCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("unregister", "passwd");

    private final XalarLoginPlugin plugin;

    public AdminCommand(XalarLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("unregister")) {
            handleUnregister(sender, args[1]);
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("passwd")) {
            handlePasswd(sender, args[1], args[2]);
            return true;
        }
        sender.sendMessage(plugin.message("admin-usage"));
        return true;
    }

    private void handleUnregister(CommandSender sender, String targetName) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int deleted;
            try {
                deleted = plugin.database().deleteByName(targetName);
            } catch (SQLException e) {
                plugin.getLogger().severe("删除玩家 " + targetName + " 的账号失败: " + e.getMessage());
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> sender.sendMessage(plugin.message("db-error")));
                return;
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                // 解除锁定放在「找不到记录」之前：离线模式下别人可以拿一个名字反复输错密码把它锁掉，
                // 而管理员多半正是为了解锁才敲这条命令。放在后面的话，账号已经不存在的名字就解不开了
                plugin.throttle().clear(targetName, null);
                if (deleted == 0) {
                    sender.sendMessage(plugin.message("admin-player-not-found", "{player}", targetName));
                    return;
                }
                // 条数可能大于 1：离线模式下大小写不同的名字是不同 UUID，而删除按名字忽略大小写
                sender.sendMessage(plugin.message("admin-unregister-success",
                        "{player}", targetName, "{count}", String.valueOf(deleted)));
                for (Player online : plugin.getServer().getOnlinePlayers()) {
                    if (online.getName().equalsIgnoreCase(targetName)) {
                        online.kick(plugin.bareMessage("kick-unregistered"));
                    }
                }
            });
        });
    }

    private void handlePasswd(CommandSender sender, String targetName, String newPassword) {
        int minLength = plugin.getConfig().getInt("min-password-length", 6);
        if (newPassword.length() < minLength) {
            sender.sendMessage(plugin.message("password-too-short", "{min}", String.valueOf(minLength)));
            return;
        }
        // 先抢占在线同名玩家的会话，和 /changepw 用的是同一把锁：两者并撞时数据库会留下
        // 最后写入的哈希、会话缓存会留下最后回调的那个，不互斥的话这两者可能对不上。
        // 目标不在线时没有会话可锁，列表为空即可。
        List<Session> claimed = claimOnlineSessions(targetName);
        if (claimed == null) {
            sender.sendMessage(plugin.message("admin-target-busy", "{player}", targetName));
            return;
        }
        int iterations = plugin.hashIterations();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String newHash = PasswordHasher.hash(newPassword, iterations);
            int updated;
            try {
                updated = plugin.database().updatePasswordByName(targetName, newHash);
            } catch (SQLException e) {
                plugin.getLogger().severe("修改玩家 " + targetName + " 的密码失败: " + e.getMessage());
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    release(claimed);
                    sender.sendMessage(plugin.message("db-error"));
                });
                return;
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                release(claimed);
                // 同 unregister：解锁要在「找不到记录」之前，否则被恶意锁定又没有账号记录的
                // 名字就解不开了
                plugin.throttle().clear(targetName, null);
                if (updated == 0) {
                    sender.sendMessage(plugin.message("admin-player-not-found", "{player}", targetName));
                    return;
                }
                sender.sendMessage(plugin.message("admin-passwd-success",
                        "{player}", targetName, "{count}", String.valueOf(updated)));
                refreshOnlineSessions(targetName, newHash);
            });
        });
    }

    /**
     * 抢占所有在线同名玩家的会话锁。
     *
     * @return 抢到的会话；其中任何一个正忙就全部退回并返回 null
     */
    private List<Session> claimOnlineSessions(String targetName) {
        List<Session> claimed = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!online.getName().equalsIgnoreCase(targetName)) {
                continue;
            }
            Session session = plugin.sessions().get(online.getUniqueId());
            if (session == null) {
                continue;
            }
            if (!session.busy.compareAndSet(false, true)) {
                release(claimed);
                return null;
            }
            claimed.add(session);
        }
        return claimed;
    }

    private static void release(List<Session> claimed) {
        for (Session session : claimed) {
            session.busy.set(false);
        }
    }

    /**
     * 同步在线玩家的会话缓存。登录校验读的是进服时缓存的哈希而不是数据库，
     * 不更新的话被改密的玩家仍能用旧密码登录，而新密码反而不认。
     */
    private void refreshOnlineSessions(String targetName, String newHash) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!online.getName().equalsIgnoreCase(targetName)) {
                continue;
            }
            Session session = plugin.sessions().get(online.getUniqueId());
            if (session != null) {
                session.passwordHash = newHash;
            }
            online.sendMessage(plugin.message("admin-passwd-notify"));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(prefix)).toList();
        }
        if (args.length == 2 && SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            // 只补全在线玩家。两条命令都支持离线玩家，但补全离线名字要查库，
            // 而 Tab 补全跑在主线程，不能为了这点便利引入一次同步 IO
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        // 第三个参数是新密码，不做任何补全提示
        return List.of();
    }
}
