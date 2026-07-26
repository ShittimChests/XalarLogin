package site.bluearchive.xalarlogin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
        /** 进服时从数据库缓存的密码哈希，null 表示未注册 */
        public volatile String passwordHash;
        public final AtomicInteger failedAttempts = new AtomicInteger();
        public volatile BukkitTask remindTask;
        public volatile BukkitTask timeoutTask;

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

    public Session create(UUID uuid) {
        Session old = sessions.put(uuid, new Session());
        if (old != null) {
            old.cancelTasks();
        }
        return sessions.get(uuid);
    }

    public Session get(UUID uuid) {
        return sessions.get(uuid);
    }

    public boolean isLoggedIn(UUID uuid) {
        Session session = sessions.get(uuid);
        return session != null && session.phase == Phase.LOGGED_IN;
    }

    public void markLoggedIn(UUID uuid) {
        Session session = sessions.get(uuid);
        if (session != null) {
            session.phase = Phase.LOGGED_IN;
            session.failedAttempts.set(0);
            session.cancelTasks();
        }
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
