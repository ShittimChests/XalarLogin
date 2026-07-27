package site.bluearchive.xalarlogin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** 在线玩家的认证状态。所有状态变更都应在主线程进行。 */
public final class SessionManager {

    public enum Phase {
        /** 正在从数据库加载账号数据 */
        LOADING,
        /** 未注册，等待 /reg */
        NEED_REGISTER,
        /** 已注册未登录，等待 /a */
        NEED_LOGIN,
        /** 已通过认证 */
        LOGGED_IN
    }

    public static final class Session {
        public volatile Phase phase = Phase.LOADING;
        /** 进服时从数据库缓存的密码哈希，null 表示未注册。改写请走 {@link #setPasswordHash} */
        public volatile String passwordHash;
        /**
         * {@link #passwordHash} 被改写过几次。进服的异步加载在派发查库前记下它，
         * 回调里发现变了就放弃写回——那说明这期间 /changepw 或 /xalar passwd 已经
         * 写进了更新的哈希，而自己手上是查库那一刻的旧快照。
         *
         * <p>不能靠「回调必然落在写入之后的 tick」来免掉这个判断：单连接 + synchronized
         * 只串行化 SQL，两个 worker 从 SQL 返回到调用 runTask 之间没有任何同步，
         * 调度器按 runTask 的调用顺序排队，不按 SQL 的返回顺序。
         */
        public final AtomicInteger passwordGeneration = new AtomicInteger();
        /**
         * 是否有 PBKDF2/SQL 异步任务正在处理本会话的注册、登录或改密。
         * 用 CAS 抢占，防止连发命令把几十毫秒一次的 PBKDF2 堆满异步线程池，
         * 也防止多个任务基于同一份旧状态各自算出结果互相覆盖。
         */
        public final AtomicBoolean busy = new AtomicBoolean();
        public volatile BukkitTask remindTask;
        public volatile BukkitTask timeoutTask;

        /**
         * 改写缓存的密码哈希并推进代号。除了进服加载那条路径（它要先比对代号，
         * 见 {@link #passwordGeneration}），所有写入都应该走这里。
         */
        public void setPasswordHash(String hash) {
            passwordHash = hash;
            passwordGeneration.incrementAndGet();
        }

        void cancelTasks() {
            if (remindTask != null) {
                remindTask.cancel();
                remindTask = null;
            }
            if (timeoutTask != null) {
                timeoutTask.cancel();
                timeoutTask = null;
            }
        }
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /** @return 新建的会话本身；调用方应持有它，异步回调回来时用 {@code ==} 确认会话没被换掉 */
    public Session create(UUID uuid) {
        Session session = new Session();
        Session old = sessions.put(uuid, session);
        if (old != null) {
            old.cancelTasks();
        }
        return session;
    }

    public Session get(UUID uuid) {
        return sessions.get(uuid);
    }

    public boolean isLoggedIn(UUID uuid) {
        Session session = sessions.get(uuid);
        return session != null && session.phase == Phase.LOGGED_IN;
    }

    /**
     * 本实例是否正在冻结这个玩家。
     *
     * <p>和 {@code !isLoggedIn(uuid)} 不同：没有会话时这里返回 false。用于「停用插件前把被我们
     * 冻结的人踢下线」——没有会话的玩家不归我们管，把他们一起踢掉是误伤。
     */
    public boolean isFrozen(UUID uuid) {
        Session session = sessions.get(uuid);
        return session != null && session.phase != Phase.LOGGED_IN;
    }

    /**
     * 标记为已登录，并刷新客户端命令列表——登录期间 PlayerCommandSendEvent
     * 把补全列表裁剪成只剩 /reg 与 /a，放行后要还回去。
     */
    public void markLoggedIn(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.phase = Phase.LOGGED_IN;
            session.cancelTasks();
        }
        player.updateCommands();
    }

    public void remove(UUID uuid) {
        Session session = sessions.remove(uuid);
        if (session != null) {
            session.cancelTasks();
        }
    }

    public void clear() {
        sessions.values().forEach(Session::cancelTasks);
        sessions.clear();
    }
}
