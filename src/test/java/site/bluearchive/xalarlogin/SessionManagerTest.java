package site.bluearchive.xalarlogin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import site.bluearchive.xalarlogin.SessionManager.Session;

/**
 * {@link Session} 的纯逻辑部分。
 *
 * <p>只测不碰 Bukkit 的那些：{@code SessionManager} 本身的方法要么收 {@code Player}、
 * 要么会取消 {@code BukkitTask}，脱离服务端跑不了；但密码代号这套是纯字段操作，
 * 而它正是「进服加载回调会不会用旧快照覆盖管理员刚设的新密码」这条不变式的全部依据。
 */
class SessionManagerTest {

    @Test
    @DisplayName("新会话的初始状态：LOADING、无哈希、未被抢占")
    void freshSessionState() {
        Session session = new Session();
        assertEquals(SessionManager.Phase.LOADING, session.phase);
        assertEquals(null, session.passwordHash);
        assertFalse(session.busy.get());
    }

    @Test
    @DisplayName("setPasswordHash 每次都推进代号")
    void setPasswordHashAdvancesGeneration() {
        Session session = new Session();
        int before = session.passwordGeneration.get();

        session.setPasswordHash("hash-1");
        assertEquals("hash-1", session.passwordHash);
        assertNotEquals(before, session.passwordGeneration.get(), "写入之后代号必须变");

        int afterFirst = session.passwordGeneration.get();
        session.setPasswordHash("hash-2");
        assertNotEquals(afterFirst, session.passwordGeneration.get(), "第二次写入同样要推进");
    }

    @Test
    @DisplayName("代号相同就说明这期间没人改过密码，进服回调可以安全写回快照")
    void generationUnchangedMeansSnapshotStillValid() {
        // 复刻 RestrictionListener.initializePlayer 的用法：派发查库前记代号，回调里比对
        Session session = new Session();
        int generation = session.passwordGeneration.get();

        // 期间没有任何 /changepw 或 /xalar passwd
        assertTrue(session.passwordGeneration.get() == generation);
        session.passwordHash = "从数据库读回来的哈希";
        assertEquals("从数据库读回来的哈希", session.passwordHash);
    }

    @Test
    @DisplayName("代号变了说明 /xalar passwd 抢先落地，旧快照必须整份作废")
    void generationChangedInvalidatesWholeSnapshot() {
        // 这是「数据库里是新密码、会话缓存却是旧密码」那个 bug 的回归：
        // 没有代号比对的话，进服回调会无条件把查库那一刻的旧哈希写回去，
        // 玩家从此只能用旧密码登录，管理员刚设的新密码反而不认。
        Session session = new Session();
        int generation = session.passwordGeneration.get();

        // 管理员在查库返回与主线程回调之间把新哈希写进了同一个会话
        session.setPasswordHash("管理员刚设的新哈希");

        assertFalse(session.passwordGeneration.get() == generation, "代号必须已经变了");
        // 回调发现代号变了 → 放弃写回，新哈希得以保留
        assertEquals("管理员刚设的新哈希", session.passwordHash);
    }

    @Test
    @DisplayName("busy 是 CAS 抢占：抢到的只有一个，释放后才能再抢")
    void busyIsExclusive() {
        Session session = new Session();
        assertTrue(session.busy.compareAndSet(false, true), "第一次应该抢得到");
        assertFalse(session.busy.compareAndSet(false, true), "已被占用时必须抢不到");

        session.busy.set(false);
        assertTrue(session.busy.compareAndSet(false, true), "释放之后可以重新抢");
    }
}
