package site.bluearchive.xalarlogin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨会话的登录失败节流。
 *
 * <p>失败计数如果只存在 Session 里，玩家被踢出后重连就会清零，等于可以无限次爆破。
 * 这里按「玩家名」和「来源 IP」各记一份，重连不会重置。
 * 纯内存，重启服务端后清空——爆破成本已经被 PBKDF2 和锁定窗口拉高，不值得落库。
 *
 * <p><b>两个维度的计数分开返回、分开锁定</b>，由调用方各用各的阈值。取两者最大值再用同一个
 * 阈值判定的话，NAT / CGNAT 后面共用一个出口 IP 的玩家会互相牵连：一个人输错三次密码，
 * 同一栋楼的所有人都进不来。IP 维度的阈值应当明显宽于玩家名维度。
 *
 * <p><b>线程约定</b>：计数与锁定的写入只发生在主线程（{@code LoginCommand} 的回调、
 * 管理命令的回调），但{@link #remainingLockSeconds}是从 {@code AsyncPlayerPreLoginEvent}
 * 读的，跑在异步线程上。ConcurrentHashMap 只保证 map 结构本身的可见性，不保证之后对
 * Counter 内部字段的普通写对其他线程可见，所以 {@code lockedUntil} 必须是 volatile ——
 * 否则进服拦截可能读到过期的 0 而把已被锁定的来源放行。
 */
public final class LoginThrottle {

    /** 当前这一轮里，该玩家名与该 IP 各自累计的失败次数。取不到的维度记 0。 */
    public record Failures(int byName, int byIp) {
    }

    /** 两个维度各自是否越线。只有越线的那个维度该被锁，见 {@link #exceeded}。 */
    public record Exceeded(boolean byName, boolean byIp) {
    }

    /**
     * 阈值判定。放在这里而不是留在命令类里，是因为「两个维度各用各的阈值」是本类的核心契约，
     * 而命令类依赖 Bukkit、没法脱离服务端测试——判定逻辑跟着它走的话，有人把两个维度改回
     * 「取最大值套同一个阈值」时不会有任何测试变红，而线上表现是同一个 NAT 出口后面的
     * 几十个玩家被其中一人的三次手滑一起锁在门外。
     *
     * @param maxAttempts 玩家名维度的阈值
     * @param ipFactor    IP 维度的阈值倍数，IP 阈值 = maxAttempts × ipFactor；0 表示不按 IP 锁定
     */
    public static Exceeded exceeded(Failures failures, int maxAttempts, int ipFactor) {
        return new Exceeded(
                failures.byName() >= maxAttempts,
                ipFactor > 0 && failures.byIp() >= (long) maxAttempts * ipFactor);
    }

    private static final class Counter {
        /** 只在主线程读写 */
        private int failures;
        /** 主线程写、异步的进服拦截读，见类注释 */
        private volatile long lockedUntil;
        /** 只在主线程读写 */
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
     * @return 玩家名与 IP 两个维度各自的累计失败次数
     */
    public Failures recordFailure(String name, String ip, long now, long retentionMillis) {
        purge(now, retentionMillis);
        return new Failures(
                bump(key("n:", name), now, retentionMillis),
                bump(key("i:", ip), now, retentionMillis));
    }

    private int bump(String key, long now, long retentionMillis) {
        if (key == null) {
            return 0;
        }
        Counter counter = counters.computeIfAbsent(key, ignored -> new Counter());
        if (now - counter.lastTouched > retentionMillis) {
            counter.failures = 0;
        }
        counter.failures++;
        counter.lastTouched = now;
        return counter.failures;
    }

    /** 锁定某个玩家名，期间用这个名字进服直接被拒。 */
    public void lockName(String name, long now, long lockMillis) {
        lock(key("n:", name), now, lockMillis);
    }

    /** 锁定某个 IP，期间从这个地址进服直接被拒。阈值应当明显宽于玩家名，见类注释。 */
    public void lockIp(String ip, long now, long lockMillis) {
        lock(key("i:", ip), now, lockMillis);
    }

    /**
     * 锁定的同时把计数归零：一轮尝试到此结束，等锁到期就该重新给满额次数。
     *
     * <p>别把归零去掉改回「靠保留期自然过期」——那要求保留期严格短于锁定时长，而
     * {@code LoginCommand} 给保留期设了 60 秒下限。{@code lockout-seconds} 填任何小于 60
     * 的值时，锁定到期后第一次输错就会因为计数还停在阈值上而立刻再锁一轮，无限循环，
     * 玩家实际上每个窗口只剩一次机会。
     *
     * <p>{@code lockMillis} 传 0 表示「只归零、不锁定」：{@code lockedUntil == now}，
     * {@link #remainingLockSeconds} 立刻返回 0。{@code lockout-seconds: 0} 走的就是这条，
     * 那样玩家能立即重连，且重连后仍有完整次数——不归零的话他每次只剩一次机会。
     */
    private void lock(String key, long now, long lockMillis) {
        if (key == null) {
            return;
        }
        Counter counter = counters.computeIfAbsent(key, ignored -> new Counter());
        counter.failures = 0;
        counter.lockedUntil = now + lockMillis;
        counter.lastTouched = now;
    }

    /**
     * 清除某个玩家名的失败记录与锁定（登录成功，或管理员重设了这个账号）。
     *
     * @return 是否真的清掉了一条记录；{@code /xalar unlock} 靠它区分「解开了」和「本来就没锁」
     *
     * <p><b>只清玩家名维度，这是有意的。</b>IP 计数不能由「有人在这个 IP 上成功登录了」来归零：
     * 离线模式下注册一个账号零成本，攻击者随时可以注册一个自己的名字、用自己设的密码登录一次，
     * 把 IP 计数清掉，{@code ip-lockout-factor} 那道防线就形同虚设——喷洒到阈值前一次归零，
     * IP 维度永远不会触发。
     *
     * <p>IP 维度平时只靠两条自愈路径回收：保留期内没有新的失败就当作新的一轮，以及锁定到期时
     * {@link #lock} 把计数清零。代价是共用出口 IP 的失败会在保留期内累积，这正是 IP 阈值要
     * 比玩家名阈值宽得多的原因；不想要这层防护的服务器可以把 {@code ip-lockout-factor} 设成 0，
     * 被误锁时管理员可以用 {@code /xalar unlock} 走 {@link #clearIp} 定向解除。
     */
    public boolean clearName(String name) {
        String key = key("n:", name);
        return key != null && counters.remove(key) != null;
    }

    /**
     * 清除某个 IP 的失败记录与锁定。
     *
     * <p><b>只允许管理员显式调用</b>（{@code /xalar unlock}），绝不能挂到登录成功之类的自动路径上
     * ——那正是 {@link #clearName} 的注释里说的那个洞。
     *
     * <p>没有这个出口的话，共用出口 IP 的服务器一旦越线（默认 15 次），同一栋楼的所有人要等锁定
     * 自然到期才进得来，而管理员手上没有任何定向手段。
     *
     * @return 是否真的清掉了一条记录
     */
    public boolean clearIp(String ip) {
        String key = key("i:", ip);
        return key != null && counters.remove(key) != null;
    }

    /** 丢弃既没锁定、又已经过了保留期的条目，避免 map 无上限增长。 */
    private void purge(long now, long retentionMillis) {
        counters.values().removeIf(counter ->
                counter.lockedUntil <= now && now - counter.lastTouched > retentionMillis);
    }
}
