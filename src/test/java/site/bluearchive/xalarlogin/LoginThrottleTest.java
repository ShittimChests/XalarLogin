package site.bluearchive.xalarlogin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LoginThrottle 的所有方法都把「当前时刻」当参数收，所以时间窗口的语义可以直接断言，
 * 不需要等真实时钟、也不需要服务端。这正是手工连服最难覆盖的部分。
 */
class LoginThrottleTest {

    private static final String NAME = "Steve";
    private static final String IP = "203.0.113.7";

    /** 复刻 LoginCommand.handleWrongPassword 的参数推导 */
    private static long retentionFor(long lockoutSeconds) {
        return Math.max(lockoutSeconds, 60L) * 1000L;
    }

    @Test
    @DisplayName("锁定到期后计数归零，重新给满额次数（lockout-seconds < 60 的回归）")
    void lockoutExpiryResetsCounter() {
        // lockout 30 秒时保留期被钳到 60 秒下限，比锁定时长还长。
        // 修复前：锁定到期后第一次输错时计数还停在 3，立刻再锁一轮，无限循环，
        // 玩家每个窗口实际只剩 1 次机会。
        LoginThrottle throttle = new LoginThrottle();
        long lockoutMillis = 30_000L;
        long retention = retentionFor(30);

        assertEquals(1, throttle.recordFailure(NAME, IP, 0L, retention).byName());
        assertEquals(2, throttle.recordFailure(NAME, IP, 1_000L, retention).byName());
        assertEquals(3, throttle.recordFailure(NAME, IP, 2_000L, retention).byName());
        throttle.lockName(NAME, 2_000L, lockoutMillis);

        assertEquals(30, throttle.remainingLockSeconds(NAME, IP, 2_000L), "刚锁上应还剩 30 秒");
        assertEquals(0, throttle.remainingLockSeconds(NAME, IP, 32_000L), "到期即放行");

        assertEquals(1, throttle.recordFailure(NAME, IP, 33_000L, retention).byName(),
                "锁定到期后应重新从 1 数起，而不是接着数到 4 立刻再锁");
    }

    @Test
    @DisplayName("默认 300 秒锁定同样在到期后归零")
    void defaultLockoutExpiryResetsCounter() {
        LoginThrottle throttle = new LoginThrottle();
        long retention = retentionFor(300);

        for (int i = 1; i <= 3; i++) {
            throttle.recordFailure(NAME, IP, i * 1_000L, retention);
        }
        throttle.lockName(NAME, 3_000L, 300_000L);

        assertEquals(0, throttle.remainingLockSeconds(NAME, IP, 303_000L));
        assertEquals(1, throttle.recordFailure(NAME, IP, 304_000L, retention).byName());
    }

    @Test
    @DisplayName("玩家名与 IP 分开计数，锁一个不影响另一个")
    void dimensionsAreIndependent() {
        LoginThrottle throttle = new LoginThrottle();
        long retention = retentionFor(300);

        throttle.recordFailure("alice", IP, 0L, retention);
        throttle.recordFailure("bob", IP, 1_000L, retention);
        LoginThrottle.Failures third = throttle.recordFailure("carol", IP, 2_000L, retention);

        assertEquals(1, third.byName(), "carol 自己只错过一次");
        assertEquals(3, third.byIp(), "同一出口 IP 累计三次");

        // 只有 alice 越线被锁时，同 IP 的 bob 不该被牵连（NAT 共享出口的关键行为）
        throttle.lockName("alice", 2_000L, 300_000L);
        assertTrue(throttle.remainingLockSeconds("alice", IP, 2_000L) > 0);
        assertEquals(0, throttle.remainingLockSeconds("bob", IP, 2_000L));
    }

    @Test
    @DisplayName("锁定 IP 会挡住该地址上的所有玩家名")
    void lockingIpBlocksEveryName() {
        LoginThrottle throttle = new LoginThrottle();
        throttle.lockIp(IP, 0L, 300_000L);

        assertEquals(300, throttle.remainingLockSeconds("anyone", IP, 0L));
        assertEquals(0, throttle.remainingLockSeconds("anyone", "198.51.100.1", 0L));
    }

    @Test
    @DisplayName("IP 为 null 时只按玩家名计数，不影响其他人")
    void nullIpIsIgnored() {
        LoginThrottle throttle = new LoginThrottle();
        long retention = retentionFor(300);

        LoginThrottle.Failures failures = throttle.recordFailure(NAME, null, 0L, retention);
        assertEquals(1, failures.byName());
        assertEquals(0, failures.byIp(), "取不到地址的玩家不参与 IP 维度");

        throttle.lockIp(null, 0L, 300_000L);
        assertEquals(0, throttle.remainingLockSeconds("other", null, 0L));
    }

    @Test
    @DisplayName("玩家名不区分大小写")
    void nameIsCaseInsensitive() {
        LoginThrottle throttle = new LoginThrottle();
        long retention = retentionFor(300);

        throttle.recordFailure("Steve", IP, 0L, retention);
        assertEquals(2, throttle.recordFailure("STEVE", IP, 1_000L, retention).byName());

        throttle.lockName("steve", 1_000L, 300_000L);
        assertTrue(throttle.remainingLockSeconds("StEvE", null, 1_000L) > 0);
    }

    @Test
    @DisplayName("登录成功后 clear 抹掉两个维度")
    void clearRemovesBothDimensions() {
        LoginThrottle throttle = new LoginThrottle();
        long retention = retentionFor(300);

        throttle.recordFailure(NAME, IP, 0L, retention);
        throttle.recordFailure(NAME, IP, 1_000L, retention);
        throttle.clear(NAME, IP);

        LoginThrottle.Failures after = throttle.recordFailure(NAME, IP, 2_000L, retention);
        assertEquals(1, after.byName());
        assertEquals(1, after.byIp());
    }

    @Test
    @DisplayName("长时间没动静的一轮会被当成新的一轮")
    void staleRoundStartsOver() {
        LoginThrottle throttle = new LoginThrottle();
        long retention = retentionFor(300);

        throttle.recordFailure(NAME, IP, 0L, retention);
        throttle.recordFailure(NAME, IP, 1_000L, retention);
        assertEquals(1, throttle.recordFailure(NAME, IP, retention + 2_000L, retention).byName());
    }

    @Test
    @DisplayName("purge 不会清掉仍在锁定中的条目")
    void purgeKeepsActiveLocks() {
        LoginThrottle throttle = new LoginThrottle();
        long retention = retentionFor(300);

        throttle.lockName(NAME, 0L, 600_000L);
        // 让别人的失败触发 purge，此时 NAME 的 lastTouched 已经远超保留期
        throttle.recordFailure("someone-else", "198.51.100.9", retention + 1_000L, retention);

        assertTrue(throttle.remainingLockSeconds(NAME, null, retention + 1_000L) > 0,
                "锁还没到期就不能被 purge 掉");
    }

    @Test
    @DisplayName("剩余秒数向上取整，避免显示 0 秒却仍被拦")
    void remainingSecondsRoundsUp() {
        LoginThrottle throttle = new LoginThrottle();
        throttle.lockName(NAME, 0L, 1_500L);

        assertEquals(2, throttle.remainingLockSeconds(NAME, null, 0L));
        assertEquals(1, throttle.remainingLockSeconds(NAME, null, 1_000L));
        assertEquals(0, throttle.remainingLockSeconds(NAME, null, 1_500L));
    }
}
