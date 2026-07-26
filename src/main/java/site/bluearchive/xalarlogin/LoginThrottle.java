package site.bluearchive.xalarlogin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨会话的登录失败节流。
 *
 * <p>失败计数如果只存在 Session 里，玩家被踢出后重连就会清零，等于可以无限次爆破。
 * 这里按「玩家名」和「来源 IP」各记一份，两者取最严格的那个，重连不会重置。
 * 纯内存，重启服务端后清空——爆破成本已经被 PBKDF2 和锁定窗口拉高，不值得落库。
 *
 * <p>状态变更都在主线程发生，但用 ConcurrentHashMap 以便将来放宽。
 */
public final class LoginThrottle {

    private static final class Counter {
        private int failures;
        private long lockedUntil;
        private long lastTouched;
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /** 玩家名不区分大小写，IP 原样；null 会被忽略（取不到地址时不参与节流）。 */
    private static String key(String prefix, String value) {
        return value == null ? null : prefix + value.toLowerCase(Locale.ROOT);
    }

    /** @return 该来源仍需等待的秒数，0 表示未被锁定 */
    public long remainingLockSeconds(String name, String ip, long now) {
        long latest = 0;
        for (String key : new String[]{key("n:", name), key("i:", ip)}) {
            if (key == null) {
                continue;
            }
            Counter counter = counters.get(key);
            if (counter != null) {
                latest = Math.max(latest, counter.lockedUntil - now);
            }
        }
        return latest <= 0 ? 0 : (latest + 999) / 1000;
    }

    /**
     * 记一次密码错误。
     *
     * @param retentionMillis 距上次失败超过这个时长就当作全新的一轮，计数从头开始
     * @return 该来源当前这一轮累计的失败次数（名字与 IP 取较大者）
     */
    public int recordFailure(String name, String ip, long now, long retentionMillis) {
        purge(now, retentionMillis);
        int worst = 0;
        for (String key : new String[]{key("n:", name), key("i:", ip)}) {
            if (key == null) {
                continue;
            }
            Counter counter = counters.computeIfAbsent(key, ignored -> new Counter());
            if (now - counter.lastTouched > retentionMillis) {
                counter.failures = 0;
            }
            counter.failures++;
            counter.lastTouched = now;
            worst = Math.max(worst, counter.failures);
        }
        return worst;
    }

    /** 达到上限后把该来源锁定一段时间，期间进服直接踢出。 */
    public void lock(String name, String ip, long now, long lockMillis) {
        for (String key : new String[]{key("n:", name), key("i:", ip)}) {
            if (key == null) {
                continue;
            }
            Counter counter = counters.computeIfAbsent(key, ignored -> new Counter());
            counter.lockedUntil = now + lockMillis;
            counter.lastTouched = now;
        }
    }

    /** 登录成功，清除该来源的失败记录。 */
    public void clear(String name, String ip) {
        for (String key : new String[]{key("n:", name), key("i:", ip)}) {
            if (key != null) {
                counters.remove(key);
            }
        }
    }

    /** 丢弃既没锁定、又已经过了保留期的条目，避免 map 无上限增长。 */
    private void purge(long now, long retentionMillis) {
        counters.values().removeIf(counter ->
                counter.lockedUntil <= now && now - counter.lastTouched > retentionMillis);
    }
}
