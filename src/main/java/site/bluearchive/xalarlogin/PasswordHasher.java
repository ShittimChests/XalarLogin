package site.bluearchive.xalarlogin;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2WithHmacSHA256 密码哈希。
 * 存储格式：iterations:base64(salt):base64(hash)
 * 计算耗时较长（有意为之），必须在异步线程调用。
 */
public final class PasswordHasher {

    /** 低于这个迭代数拒绝使用，防止配置写错把密码保护降到无意义的水平 */
    public static final int MIN_ITERATIONS = 100_000;
    /** OWASP 对 PBKDF2-HMAC-SHA256 的建议值 */
    public static final int DEFAULT_ITERATIONS = 600_000;
    /**
     * 迭代数上限。{@link #hash} 与 {@link #verify} 必须用同一个上限：
     * 只在 verify 侧设限的话，配置里多打一个 0 就会写出 verify 永远拒绝的哈希，
     * 表现成「注册成功了但密码永远错」，而且改密和管理员重设都会落进同一个坑。
     */
    public static final int MAX_ITERATIONS = 10_000_000;

    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /**
     * 迭代数写进哈希串本身，所以调高配置不会让老账号失效——
     * 老密码继续按注册时的迭代数校验，重新注册或改密码才会用上新值。
     */
    public static String hash(String password, int iterations) {
        int effective = clampIterations(iterations);
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, effective);
        Base64.Encoder encoder = Base64.getEncoder();
        return effective + ":" + encoder.encodeToString(salt) + ":" + encoder.encodeToString(hash);
    }

    /** 把配置里的迭代数收进 {@code [MIN_ITERATIONS, MAX_ITERATIONS]}。 */
    public static int clampIterations(int iterations) {
        return Math.min(MAX_ITERATIONS, Math.max(MIN_ITERATIONS, iterations));
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] parts = stored.split(":");
            if (parts.length != 3) {
                return false;
            }
            int iterations = Integer.parseInt(parts[0]);
            // 迭代数来自数据库，损坏或被篡改的值会让校验线程空转很久
            if (iterations < 1 || iterations > MAX_ITERATIONS) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 不可用", e);
        }
    }
}
