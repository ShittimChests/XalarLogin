package site.bluearchive.xalarlogin.listener;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import site.bluearchive.xalarlogin.LoginThrottle;
import site.bluearchive.xalarlogin.SessionManager.Phase;
import site.bluearchive.xalarlogin.SessionManager.Session;
import site.bluearchive.xalarlogin.XalarLoginPlugin;
import site.bluearchive.xalarlogin.storage.Database;

/**
 * 未登录玩家的行为冻结，以及进服/退服的会话生命周期。
 *
 * <p>冻结类 handler 都跑在 LOWEST 且<b>不</b>加 {@code ignoreCancelled}：认证判定必须早于任何
 * 第三方插件，而聊天频道、命令别名之类的插件常见做法正是「先取消事件、再用自己的逻辑把内容放出去」。
 * 它们如果同样注册在 LOWEST 且先于本插件加载，加了 {@code ignoreCancelled} 就会让本插件被跳过，
 * 未认证玩家的发言/命令照样生效——那正好抵消了选 LOWEST 的意义。
 * 代价是别的插件已经取消过的事件我们还会再处理一遍：纯拦截的 handler 重复取消没有副作用，
 * 而 {@link #onChat} 与 {@link #onCommand} 会多发一条「请先登录」提示。这个代价是有意接受的
 * ——漏判一次等于放行未认证玩家，多发一条提示只是啰嗦。
 */
public final class RestrictionListener implements Listener {

    private final XalarLoginPlugin plugin;
    /**
     * 未登录时允许执行的命令，存的是本插件注册的 {@link PluginCommand} <b>实例</b>。
     *
     * <p>不能存字符串再拿标签去比：标签到命令的映射由服务端决定，而同一个名字可能属于别的插件。
     * 剥掉命名空间比字符串的话，只要服务器上还有任何一条叫 a / l / login / reg / register 的
     * 第三方命令，未认证玩家就能用 {@code /<plugin>:<cmd>} 的形式把它执行掉——离线模式下他
     * 还可以顶着管理员的名字进来，那条命令会带着管理员权限跑。
     */
    private final Set<Command> authCommands;

    public RestrictionListener(XalarLoginPlugin plugin) {
        this.plugin = plugin;
        this.authCommands = collectAuthCommands(plugin);
    }

    /** 直接读注册结果，避免白名单和 plugin.yml 各改各的而对不上。 */
    private static Set<Command> collectAuthCommands(XalarLoginPlugin plugin) {
        Set<Command> commands = new HashSet<>();
        for (String key : List.of("reg", "a")) {
            PluginCommand command = plugin.getCommand(key);
            if (command != null) {
                commands.add(command);
            }
        }
        return Set.copyOf(commands);
    }

    /**
     * 这个标签实际会派发到本插件的 /reg 或 /a 吗？
     *
     * <p>用服务端自己的命令表解析，因此别名（l、login、register）与命名空间形式
     * （xalarlogin:reg）都自动成立，而 {@code staffchat:a} 这种同名的第三方命令会解析到
     * 别的实例上，直接被拒。
     */
    private boolean isAuthCommand(String label) {
        if (label.isEmpty()) {
            return false;
        }
        Command resolved = plugin.getServer().getCommandMap()
                .getCommand(label.toLowerCase(Locale.ROOT));
        return resolved != null && authCommands.contains(resolved);
    }

    // ---------- 会话生命周期 ----------

    /**
     * 被锁定的来源在这里就挡掉，而不是等 Join 之后再踢：到了 Join 玩家已经进了世界，
     * 区块加载、进服广播、其他插件的 Join 逻辑都已经跑过一轮，反复重连即可放大这份开销。
     *
     * <p><b>本类有三个 handler 跑在异步线程上</b>：这一个、{@link #onChat}（AsyncChatEvent 本身
     * 就是异步事件）和 {@link #onAsyncTabComplete}。它们只做线程安全的事：读
     * {@link LoginThrottle}（ConcurrentHashMap，纯读）、读会话的 volatile 字段、读配置
     * （onEnable 时已在主线程加载完）、以及 Adventure 的 sendMessage。
     * <b>不要在这三个方法里改 Session 状态或调 player.kick()</b>——那两件事只能在主线程做。
     * onPreLogin 更是连 Session 都碰不得：此时 Player 对象还不存在，会话要等 Join 才建立。
     *
     * <p>用 AsyncPlayerPreLoginEvent 而不是 PlayerLoginEvent，是因为后者在 Paper 26.2 已废弃。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        long lockedFor = plugin.throttle().remainingLockSeconds(
                event.getName(), event.getAddress().getHostAddress(), System.currentTimeMillis());
        if (lockedFor > 0) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.bareMessage("kick-locked-out", "{seconds}", String.valueOf(lockedFor)));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        initializePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.sessions().remove(event.getPlayer().getUniqueId());
    }

    /** 建立会话并异步加载账号数据；同 IP 免密直接放行，否则开始提示计时。 */
    public void initializePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String currentIp = playerIp(player);

        Session session = plugin.sessions().create(uuid);
        // 超时任务从建立会话就挂上，而不是等 phase 确定：数据库卡住时会话会一直停在
        // LOADING，等到 startReminder 才计时的话玩家会被无限期冻结在原地没人管。
        startTimeout(player, session);
        // 派发查库之前记下代号，回调里用它判断这份快照有没有被 /xalar passwd 抢先作废
        int generation = session.passwordGeneration.get();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.Account account;
            try {
                account = plugin.database().findAccount(uuid);
            } catch (SQLException e) {
                plugin.getLogger().severe("加载玩家 " + name + " 的账号数据失败: " + e.getMessage());
                plugin.runOnMain(() -> {
                    if (player.isOnline()) {
                        player.kick(plugin.bareMessage("kick-db-error"));
                    }
                });
                return;
            }

            plugin.runOnMain(() -> {
                // 比对会话实例而不是只看 phase：玩家退服重连后 map 里换成了新会话，
                // 它同样处于 LOADING，光看 phase 会把上一次连接的 IP 用来判定这次的免密。
                if (plugin.sessions().get(uuid) != session || !player.isOnline() || session.phase != Phase.LOADING) {
                    return;
                }
                if (account == null) {
                    session.phase = Phase.NEED_REGISTER;
                    startReminder(player, session);
                    return;
                }
                // 代号变了说明这期间 /xalar passwd 已经把新哈希写进会话了，手上这一份是查库
                // 那一刻的旧快照，**整份都不能再用**：
                //   - 写回 passwordHash 会让玩家只能用旧密码登录、新密码反而不认；
                //   - account.lastIp() 也是清空前的旧值，而 passwd 清空它就是为了作废免密，
                //     照它放行等于让人绕过刚刚的密码重设进服。
                // 不能靠「回调必然排在写入之后」——单连接只串行化 SQL，不串行化两个 worker
                // 从 SQL 返回到 runTask 之间的那段路。代号变了就直接让他用新密码登录
                boolean snapshotStillValid = session.passwordGeneration.get() == generation;
                if (snapshotStillValid) {
                    session.passwordHash = account.passwordHash();
                }

                // 默认关闭：离线模式下谁都能用别人的名字进服，只要出口 IP 相同就免密放行，
                // 等于 NAT / CGNAT 后面的人可以互相顶号，而且完全绕过 LoginThrottle。
                boolean ipSessionEnabled = plugin.getConfig().getBoolean("ip-session-enabled", false);
                if (snapshotStillValid && ipSessionEnabled
                        && currentIp != null && currentIp.equals(account.lastIp())) {
                    plugin.sessions().markLoggedIn(player);
                    plugin.throttle().clearName(name);
                    player.sendMessage(plugin.message("auto-login"));
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            plugin.database().updateLastLogin(uuid, currentIp, account.passwordHash());
                        } catch (SQLException e) {
                            plugin.getLogger().warning("更新玩家 " + name + " 登录时间失败: " + e.getMessage());
                        }
                    });
                    return;
                }
                session.phase = Phase.NEED_LOGIN;
                startReminder(player, session);
            });
        });
    }

    /** @return 玩家来源 IP，取不到（极端情况）返回 null，此时该玩家不参与免密与 IP 节流 */
    public static String playerIp(Player player) {
        InetSocketAddress socket = player.getAddress();
        // 两层都要判空：SocketAddress 本身可能没有，它内部的 InetAddress 在地址未解析时也是 null
        InetAddress address = socket == null ? null : socket.getAddress();
        return address == null ? null : address.getHostAddress();
    }

    /** 认证超时兜底。免密放行与登录成功都会经 markLoggedIn 取消它。 */
    private void startTimeout(Player player, Session session) {
        long timeoutTicks = Math.max(5, plugin.getConfig().getInt("login-timeout-seconds", 60)) * 20L;
        session.timeoutTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !plugin.sessions().isLoggedIn(player.getUniqueId())) {
                player.kick(plugin.bareMessage("kick-timeout"));
            }
        }, timeoutTicks);
    }

    /** phase 定下来之后才开始催——LOADING 期间玩家还不知道该 /reg 还是 /a。 */
    private void startReminder(Player player, Session session) {
        long remindTicks = Math.max(1, plugin.getConfig().getInt("remind-interval-seconds", 5)) * 20L;
        session.remindTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Session current = plugin.sessions().get(player.getUniqueId());
            if (current == null || current.phase == Phase.LOGGED_IN) {
                return;
            }
            String key = current.phase == Phase.NEED_REGISTER ? "remind-register" : "remind-login";
            player.sendMessage(plugin.message(key));
        }, 1L, remindTicks);
    }

    /**
     * 该玩家当前是否处于冻结状态。
     *
     * <p>没有会话一律按「未认证」处理：进服流程里会话建立之前就可能收到事件（例如
     * {@link #onCommandSend}），这时宁可先冻住。**这里不做任何放宽**，认证判定必须 fail-closed。
     */
    private boolean isRestricted(Player player) {
        return !plugin.sessions().isLoggedIn(player.getUniqueId());
    }

    /**
     * 用于那些参数是<b>任意 Player 实体</b>（而不是「发起事件的那个连接」）的 handler。
     *
     * <p>Citizens 之类的插件会放置玩家型 NPC：它们是 Player，却不走登录流程、不触发
     * PlayerJoinEvent，因此永远不会有会话。只按 {@link #isRestricted} 判的话，
     * {@link #onDamage} 会把打向 NPC 的伤害全部取消——战斗 NPC 和打靶假人永远掉不了血。
     *
     * <p>判据是「服务端的在线玩家表里查得到同一个实例」：真实连接一定在表里，而玩家型 NPC 是
     * 直接塞进世界的，从不进这张表。<b>不要改用 {@code getAddress() != null}</b>——Paper 允许把
     * {@code server-ip} 配成 unix domain socket（本机 Velocity/BungeeCord 的常见部署），那种
     * 部署下 {@code getAddress()} 对<b>每一个真实玩家</b>都返回 null，整条判定会当场 fail-open。
     *
     * <p>这个放宽只用在实体类 handler 上，**不要**挪进 {@link #isRestricted}：那条路径上有
     * {@code onChat} / {@code onAsyncTabComplete} 这两个异步 handler，也包含传送冻结这类纯
     * 认证执行路径——认证判定一旦 fail-open，任何让判据落空的时刻都会变成绕过窗口。
     */
    private boolean isRestrictedEntity(Player player) {
        return isRestricted(player)
                && plugin.getServer().getPlayer(player.getUniqueId()) == player;
    }

    // ---------- 行为冻结 ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition() && isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * PlayerTeleportEvent 虽然继承 PlayerMoveEvent，却<b>自带 HandlerList</b>，
     * 所以注册在 PlayerMoveEvent 上的 {@link #onMove} 根本收不到它，必须单独拦。
     * 否则冻结中的玩家仍会被第三方插件、命令方块或原版逻辑传送走。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * PlayerPortalEvent 继承 PlayerTeleportEvent，但同样自带 HandlerList，
     * 上面那个 handler 也收不到，只能再来一个。
     *
     * <p>实际场景：玩家站在传送门方块里断线，重连后被 onMove 钉在原地，可传送门的判定
     * 走的是 entityInside 而不是移动事件，几秒后他就在未认证状态下换了维度。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPortal(PlayerPortalEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.message("must-login-first"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isRestricted(event.getPlayer())) {
            return;
        }
        if (!isAuthCommand(event.getMessage().substring(1).split(" ", 2)[0])) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.message("must-login-first"));
        }
    }

    /**
     * 未登录时把补全列表裁成只剩认证命令，避免泄露全服命令清单。
     *
     * <p>这个 handler 跑 HIGHEST 而不是 LOWEST：它不做认证判定，只是删集合元素，
     * 早跑的话后面的监听器还能把命令加回去。裁剪要最后一个动手才有意义。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandSend(PlayerCommandSendEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.getCommands().removeIf(name -> !isAuthCommand(name));
        }
    }

    /**
     * 未登录时不给任何 Tab 补全建议。
     *
     * <p>补全走的是独立的一条链路：既不经过 {@link #onCommand}（那只拦执行），也不受
     * {@link #onCommandSend} 的裁剪影响——后者只决定客户端命令树里有哪些名字，而参数补全
     * 是服务端现算的。不拦的话，冻结中的玩家输入 {@code /ban } 按 Tab 就能拿到全服玩家名，
     * {@code /warp } 能拿到全部传送点。
     *
     * <p>{@code setHandled(true)} 让服务端不再自行计算建议，同步的 TabCompleteEvent 也就不会再触发。
     * 本方法跑在异步线程上（见 {@link #onPreLogin} 的线程说明），因此只读 ConcurrentHashMap
     * 与会话的 volatile 字段。
     *
     * <p>和 {@link #onCommandSend} 同理跑 HIGHEST 而不是 LOWEST：它不做认证判定，只是把建议列表
     * 清空，早跑的话后面的监听器还能把结果填回去。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
        if (event.getSender() instanceof Player player && isRestricted(player)) {
            event.completions(List.of());
            event.setHandled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isRestrictedEntity(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * 玩家自己的背包界面是客户端本地打开的，服务端收不到 PlayerInteractEvent，
     * 所以点击之外还得单独挡住拖拽——否则未登录也能整理背包甚至用 2x2 合成格。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && isRestrictedEntity(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** 书与笔的编辑界面同样是客户端本地打开的，不经过 PlayerInteractEvent。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditBook(PlayerEditBookEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** 告示牌的编辑界面也是客户端本地打开的，服务端只会收到这个事件。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * 兜底：容器界面正常要先 PlayerInteractEvent，那条路已经堵了，但第三方插件可以直接
     * {@code openInventory()} 而不经过交互。挡在这里比逐个补 click/drag 更彻底。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && isRestrictedEntity(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDropItem(PlayerDropItemEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isRestrictedEntity(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isRestrictedEntity(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isRestrictedEntity(player)) {
            event.setCancelled(true);
        }
    }
}
