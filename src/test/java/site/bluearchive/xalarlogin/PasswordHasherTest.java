package site.bluearchive.xalarlogin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    /** 迭代数低到不值得跑真 PBKDF2 的场合都用这个下限，单次约 30 毫秒 */
    private static final int FAST = PasswordHasher.MIN_ITERATIONS;

    @Test
    @DisplayName("clampIterations 上下限对称")
    void clampIsSymmetric() {
        assertEquals(PasswordHasher.MIN_ITERATIONS, PasswordHasher.clampIterations(0));
        assertEquals(PasswordHasher.MIN_ITERATIONS, PasswordHasher.clampIterations(-1));
        assertEquals(PasswordHasher.MIN_ITERATIONS,
                PasswordHasher.clampIterations(PasswordHasher.MIN_ITERATIONS - 1));
        assertEquals(PasswordHasher.MAX_ITERATIONS,
                PasswordHasher.clampIterations(PasswordHasher.MAX_ITERATIONS + 1));
        assertEquals(PasswordHasher.MAX_ITERATIONS, PasswordHasher.clampIterations(Integer.MAX_VALUE));
        assertEquals(PasswordHasher.DEFAULT_ITERATIONS,
                PasswordHasher.clampIterations(PasswordHasher.DEFAULT_ITERATIONS));
    }

    @Test
    @DisplayName("hash 把钳过的迭代数写进哈希串，verify 因此不会拒绝自己写出来的哈希")
    void hashWritesClampedIterations() {
        // 这是「password-hash-iterations 多打一个 0 就注册成功却永远登不上」的回归：
        // hash 只钳下限、verify 却有上限时，前缀会是 99999999，verify 直接返回 false。
        String stored = PasswordHasher.hash("correct horse", PasswordHasher.MAX_ITERATIONS + 1);
        assertEquals(String.valueOf(PasswordHasher.MAX_ITERATIONS), stored.split(":")[0],
                "写进哈希串的迭代数必须落在 verify 接受的区间内");
    }

    @Test
    @DisplayName("低于下限的配置也会被钳上去并能正常校验")
    void tooLowIterationsRoundTrip() {
        String stored = PasswordHasher.hash("hunter2", 1);
        assertEquals(String.valueOf(PasswordHasher.MIN_ITERATIONS), stored.split(":")[0]);
        assertTrue(PasswordHasher.verify("hunter2", stored));
        assertFalse(PasswordHasher.verify("hunter3", stored));
    }

    @Test
    @DisplayName("存储格式是 迭代数:base64(salt):base64(hash)，salt 每次不同")
    void storageFormat() {
        String first = PasswordHasher.hash("same-password", FAST);
        String second = PasswordHasher.hash("same-password", FAST);

        assertEquals(3, first.split(":").length);
        assertNotEquals(first, second, "相同密码两次哈希必须因为 salt 不同而不同");
        assertTrue(PasswordHasher.verify("same-password", first));
        assertTrue(PasswordHasher.verify("same-password", second));
    }

    @Test
    @DisplayName("老哈希按自己记录的迭代数校验，调整配置不会让任何人登不上")
    void oldHashesSurviveIterationChange() {
        String stored = PasswordHasher.hash("legacy", FAST);
        // 配置改成别的值之后，老哈希仍然只看前缀
        assertTrue(PasswordHasher.verify("legacy", stored));
        assertEquals(String.valueOf(FAST), stored.split(":")[0]);
    }

    @Test
    @DisplayName("损坏或被篡改的哈希串返回 false 而不是抛异常")
    void malformedStoredValues() {
        assertFalse(PasswordHasher.verify("x", ""));
        assertFalse(PasswordHasher.verify("x", "no-colons-at-all"));
        assertFalse(PasswordHasher.verify("x", "100000:only-two-parts"));
        assertFalse(PasswordHasher.verify("x", "notanumber:AAAA:AAAA"));
        assertFalse(PasswordHasher.verify("x", "100000:!!!not-base64!!!:AAAA"));
        assertFalse(PasswordHasher.verify("x", "0:AAAAAAAAAAAAAAAAAAAAAA==:AAAA"));
        // 上限之外的迭代数要直接拒绝，否则一条被篡改的记录能让校验线程空转很久
        assertFalse(PasswordHasher.verify("x",
                (PasswordHasher.MAX_ITERATIONS + 1) + ":AAAAAAAAAAAAAAAAAAAAAA==:AAAA"));
    }
}
