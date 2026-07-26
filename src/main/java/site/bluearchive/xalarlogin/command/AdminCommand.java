package site.bluearchive.xalarlogin.command;

import java.sql.SQLException;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import site.bluearchive.xalarlogin.XalarLoginPlugin;

/** /xalar unregister <玩家> —— 管理命令，权限 xalarlogin.admin，控制台可用。 */
public final class AdminCommand implements TabExecutor {

    private final XalarLoginPlugin plugin;

    public AdminCommand(XalarLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (args.length != 2 || !args[0].equalsIgnoreCase("unregister")) {
            sender.sendMessage(plugin.message("admin-usage"));
            return true;
        }
        String targetName = args[1];

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean deleted;
            try {
                deleted = plugin.database().deleteByName(targetName);
            } catch (SQLException e) {
                plugin.getLogger().severe("删除玩家 " + targetName + " 的账号失败: " + e.getMessage());
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> sender.sendMessage(plugin.message("db-error")));
                return;
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!deleted) {
                    sender.sendMessage(plugin.message("admin-player-not-found", "{player}", targetName));
                    return;
                }
                sender.sendMessage(plugin.message("admin-unregister-success", "{player}", targetName));
                Player online = plugin.getServer().getPlayerExact(targetName);
                if (online != null) {
                    online.kick(plugin.bareMessage("kick-unregistered"));
                }
            });
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) {
            return List.of("unregister");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unregister")) {
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
