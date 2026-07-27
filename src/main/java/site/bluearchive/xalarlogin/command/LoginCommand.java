package site.bluearchive.xalarlogin.command;

import java.sql.SQLException;
import java.util.UUID;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import site.bluearchive.xalarlogin.LoginThrottle;
import site.bluearchive.xalarlogin.PasswordHasher;
import site.bluearchive.xalarlogin.SessionManager.Phase;
import site.bluearchive.xalarlogin.SessionManager.Session;
import site.bluearchive.xalarlogin.XalarLoginPlugin;
import site.bluearchive.xalarlogin.listener.RestrictionListener;

/** /a &lt;密码&gt; */
public final class LoginCommand implements CommandExecutor {

    /** lockout-seconds 的上界（30 天）。见 {@link #handleWrongPassword} 里的溢出说明。 */
    private static final long MAX_LOCKOUT_SECONDS = 30L * 24 * 3600;
    /** max-login-attempts 与 ip-lockout-factor 的上界，纯粹用来挡住乘法溢出 */
    private static final int MAX_ATTEMPTS = 1_000_000;

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

            plugin.runOnMain(() -> {
                session.busy.set(false);
                if (plugin.sessions().get(uuid) != session || !player.isOnline()
                        || session.phase != Phase.NEED_LOGIN) {
                    return;
                }
                if (ok) {
                    plugin.sessions().markLoggedIn(player);
                    plugin.throttle().clearName(name);
                    player.sendMessage(plugin.message("login-success"));
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            plugin.database().updateLastLogin(uuid, ip, storedHash);
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

    /**
     * 锁定要留痕：被锁的 IP 只在这里出现过一次，不记的话管理员根本不知道该给
     * {@code /xalar unlock} 传什么——被锁的人已经连不进来，Tab 补全也列不出他们。
     */
    private void logLock(String what, long seconds) {
        plugin.getLogger().warning("登录失败次数超限，已锁定 " + what + " " + seconds
                + " 秒；如需提前解除：/xalar unlock <玩家名|IP>");
    }

    /** 失败计数记在 LoginThrottle 而非 Session 上，这样踢出后重连也不会清零。 */
    private void handleWrongPassword(Player player, String name, String ip) {
        // 三个旋钮都要双侧钳：下面 maxAttempts × ipFactor 与 lockoutSeconds × 1000
        // 都会做乘法，只钳下界的话配置里填个极大值就会溢出，锁定反而彻底失效
        int maxAttempts = Math.clamp(plugin.getConfig().getInt("max-login-attempts", 3),
                1, MAX_ATTEMPTS);
        // 上界必须钳：下面要乘 1000 变成毫秒，配置里填个很大的数会溢出成负数，
        // lockedUntil = now + 负数 → remainingLockSeconds 立刻返回 0，锁定彻底失效。
        // 30 天足够覆盖任何合理用法，超出的按 30 天算
        long lockoutSeconds = Math.clamp(plugin.getConfig().getLong("lockout-seconds", 300),
                0L, MAX_LOCKOUT_SECONDS);
        int ipFactor = Math.clamp(plugin.getConfig().getInt("ip-lockout-factor", 5), 0, MAX_ATTEMPTS);
        long now = System.currentTimeMillis();
        // 保留期只负责「一轮没打满就长时间没动静，当作新的一轮」。锁定到期后的归零不靠它，
        // 由 LoginThrottle.lock() 直接把计数清掉——否则这里的 60 秒下限会在
        // lockout-seconds 小于 60 时让计数永远停在阈值上，一输错就立刻再锁一轮。
        long retentionMillis = Math.max(lockoutSeconds, 60L) * 1000L;

        LoginThrottle.Failures failures = plugin.throttle().recordFailure(name, ip, now, retentionMillis);
        // IP 维度用高得多的阈值：宿舍、家庭 NAT、运营商 CGNAT 后面几十个玩家共用一个出口 IP，
        // 跟玩家名共用阈值的话，一个人输错三次就把所有人锁在门外五分钟。
        // 判定本身在 LoginThrottle 里，那样才测得到，别挪回来
        LoginThrottle.Exceeded exceeded = LoginThrottle.exceeded(failures, maxAttempts, ipFactor);

        if (!exceeded.byName() && !exceeded.byIp()) {
            player.sendMessage(plugin.message("wrong-password",
                    "{remaining}", String.valueOf(maxAttempts - failures.byName())));
            return;
        }
        // 越线的那个维度到此结束一轮，计数必须归零，否则它会一直停在阈值上，玩家每次重连
        // 只剩一次机会。lockMillis 传 0 就是「只归零、不锁定」（lockedUntil == now，
        // remainingLockSeconds 立刻返回 0），lockout-seconds: 0 那条路径要的正是这个。
        // 只处理真正越线的那个维度：被 IP 阈值兜住时不该连带把这个玩家名也锁上
        long lockMillis = lockoutSeconds * 1000L;
        if (exceeded.byName()) {
            plugin.throttle().lockName(name, now, lockMillis);
            logLock("玩家名 " + name, lockoutSeconds);
        }
        if (exceeded.byIp()) {
            plugin.throttle().lockIp(ip, now, lockMillis);
            logLock("IP " + ip, lockoutSeconds);
        }
        player.kick(lockoutSeconds > 0
                ? plugin.bareMessage("kick-locked-out", "{seconds}", String.valueOf(lockoutSeconds))
                : plugin.bareMessage("kick-too-many-attempts"));
    }
}
