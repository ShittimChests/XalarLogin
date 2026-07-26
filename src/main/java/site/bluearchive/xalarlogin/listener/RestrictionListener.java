package site.bluearchive.xalarlogin.listener;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import site.bluearchive.xalarlogin.SessionManager.Phase;
import site.bluearchive.xalarlogin.SessionManager.Session;
import site.bluearchive.xalarlogin.XalarLoginPlugin;
import site.bluearchive.xalarlogin.storage.Database;

/**
 * 未登录玩家的行为冻结，以及进服/退服的会话生命周期。
 *
 * <p>所有 handler 都跑在 LOWEST：认证判定必须早于任何第三方插件，否则聊天记录插件、
 * 命令别名插件之类会先一步处理未认证玩家的输入，而它们大多不看事件是否被取消。
 */
public final class RestrictionListener implements Listener {

    private final XalarLoginPlugin plugin;
    /** 未登录时允许执行的命令（不含斜杠，已去掉命名空间），从 plugin.yml 实际注册的命令派生 */
    private final Set<String> allowedCommands;

    public RestrictionListener(XalarLoginPlugin plugin) {
        this.plugin = plugin;
        this.allowedCommands = collectAuthCommands(plugin);
    }

    /** 直接读注册结果，避免白名单和 plugin.yml 里的别名各改各的而对不上。 */
    private static Set<String> collectAuthCommands(XalarLoginPlugin plugin) {
        Set<String> names = new HashSet<>();
        for (String key : List.of("reg", "a")) {
            PluginCommand command = plugin.getCommand(key);
            if (command == null) {
                continue;
            }
            names.add(command.getName().toLowerCase(Locale.ROOT));
            for (String alias : command.getAliases()) {
                names.add(alias.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(names);
    }

    // ---------- 会话生命周期 ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        initializePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.sessions().remove(event.getPlayer().getUniqueId());
    }

    /** 建立会话并异步加载账号数据；同 IP 免密直接放行，否则开始提示与超时计时。 */
    public void initializePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String currentIp = playerIp(player);

        long lockedFor = plugin.throttle().remainingLockSeconds(name, currentIp, System.currentTimeMillis());
        if (lockedFor > 0) {
            player.kick(plugin.bareMessage("kick-locked-out", "{seconds}", String.valueOf(lockedFor)));
            return;
        }

        Session session = plugin.sessions().create(uuid);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.Account account;
            try {
                account = plugin.database().findAccount(uuid);
            } catch (SQLException e) {
                plugin.getLogger().severe("加载玩家 " + name + " 的账号数据失败: " + e.getMessage());
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.kick(plugin.bareMessage("kick-db-error"));
                    }
                });
                return;
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                // 比对会话实例而不是只看 phase：玩家退服重连后 map 里换成了新会话，
                // 它同样处于 LOADING，光看 phase 会把上一次连接的 IP 用来判定这次的免密。
                if (plugin.sessions().get(uuid) != session || !player.isOnline() || session.phase != Phase.LOADING) {
                    return;
                }
                if (account == null) {
                    session.phase = Phase.NEED_REGISTER;
                    startAuthTasks(player, session);
                    return;
                }
                session.passwordHash = account.passwordHash();

                boolean ipSessionEnabled = plugin.getConfig().getBoolean("ip-session-enabled", true);
                if (ipSessionEnabled && currentIp != null && currentIp.equals(account.lastIp())) {
                    plugin.sessions().markLoggedIn(player);
                    plugin.throttle().clear(name, currentIp);
                    player.sendMessage(plugin.message("auto-login"));
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            plugin.database().updateLastLogin(uuid, currentIp);
                        } catch (SQLException e) {
                            plugin.getLogger().warning("更新玩家 " + name + " 登录时间失败: " + e.getMessage());
                        }
                    });
                    return;
                }
                session.phase = Phase.NEED_LOGIN;
                startAuthTasks(player, session);
            });
        });
    }

    /** @return 玩家来源 IP，取不到（极端情况）返回 null，此时不启用免密 */
    public static String playerIp(Player player) {
        return player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : null;
    }

    private void startAuthTasks(Player player, Session session) {
        long remindTicks = Math.max(1, plugin.getConfig().getInt("remind-interval-seconds", 5)) * 20L;
        long timeoutTicks = Math.max(5, plugin.getConfig().getInt("login-timeout-seconds", 60)) * 20L;

        session.remindTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Session current = plugin.sessions().get(player.getUniqueId());
            if (current == null || current.phase == Phase.LOGGED_IN) {
                return;
            }
            String key = current.phase == Phase.NEED_REGISTER ? "remind-register" : "remind-login";
            player.sendMessage(plugin.message(key));
        }, 1L, remindTicks);

        session.timeoutTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !plugin.sessions().isLoggedIn(player.getUniqueId())) {
                player.kick(plugin.bareMessage("kick-timeout"));
            }
        }, timeoutTicks);
    }

    private boolean isRestricted(Player player) {
        return !plugin.sessions().isLoggedIn(player.getUniqueId());
    }

    // ---------- 行为冻结 ----------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition() && isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.message("must-login-first"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isRestricted(event.getPlayer())) {
            return;
        }
        if (!allowedCommands.contains(stripNamespace(event.getMessage().substring(1).split(" ", 2)[0]))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.message("must-login-first"));
        }
    }

    /** 未登录时把补全列表裁成只剩认证命令，避免泄露全服命令清单。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandSend(PlayerCommandSendEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.getCommands().removeIf(name -> !allowedCommands.contains(stripNamespace(name)));
        }
    }

    private static String stripNamespace(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        return colon >= 0 ? lower.substring(colon + 1) : lower;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * 玩家自己的背包界面是客户端本地打开的，服务端收不到 PlayerInteractEvent，
     * 所以点击之外还得单独挡住拖拽——否则未登录也能整理背包甚至用 2x2 合成格。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** 书与笔的编辑界面同样是客户端本地打开的，不经过 PlayerInteractEvent。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEditBook(PlayerEditBookEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }
}
