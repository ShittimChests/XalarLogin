package site.bluearchive.xalarlogin.listener;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import site.bluearchive.xalarlogin.SessionManager.Phase;
import site.bluearchive.xalarlogin.SessionManager.Session;
import site.bluearchive.xalarlogin.XalarLoginPlugin;
import site.bluearchive.xalarlogin.storage.Database;

/** 未登录玩家的行为冻结，以及进服/退服的会话生命周期。 */
public final class RestrictionListener implements Listener {

    /** 未登录时允许执行的命令（不含斜杠，已去掉命名空间） */
    private static final Set<String> ALLOWED_COMMANDS = Set.of("reg", "register", "a", "l", "login");

    private final XalarLoginPlugin plugin;

    public RestrictionListener(XalarLoginPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- 会话生命周期 ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        initializePlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.sessions().remove(event.getPlayer().getUniqueId());
    }

    /** 建立会话并异步加载账号数据；同 IP 免密直接放行，否则开始提示与超时计时。 */
    public void initializePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.sessions().create(uuid);
        String currentIp = playerIp(player);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.Account account;
            try {
                account = plugin.database().findAccount(uuid);
            } catch (SQLException e) {
                plugin.getLogger().severe("加载玩家 " + player.getName() + " 的账号数据失败: " + e.getMessage());
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.kick(plugin.bareMessage("kick-db-error"));
                    }
                });
                return;
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Session session = plugin.sessions().get(uuid);
                if (session == null || !player.isOnline() || session.phase != Phase.LOADING) {
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
                    plugin.sessions().markLoggedIn(uuid);
                    player.sendMessage(plugin.message("auto-login"));
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            plugin.database().updateLastLogin(uuid, currentIp);
                        } catch (SQLException e) {
                            plugin.getLogger().warning("更新玩家 " + player.getName() + " 登录时间失败: " + e.getMessage());
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

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition() && isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.message("must-login-first"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isRestricted(event.getPlayer())) {
            return;
        }
        String command = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = command.indexOf(':');
        if (colon >= 0) {
            command = command.substring(colon + 1);
        }
        if (!ALLOWED_COMMANDS.contains(command)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.message("must-login-first"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }
}
