package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.config.CollabProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edit Session Lock 저장소 — 획득·해제·TTL lazy 만료·세션 정리를 가짜 시계로 검증한다.
 */
class LockRegistryTest {

    private final MutableClock clock = new MutableClock();
    private final LockRegistry locks = new LockRegistry(defaultProperties(), clock);

    private final LockRegistry.LockTarget table1 = new LockRegistry.LockTarget("table", "t1");

    @Test
    @DisplayName("첫 획득은 성공하고 타인의 두 번째 획득은 실패한다")
    void acquireThenCompetingAcquireFails() {
        assertThat(locks.acquire("m1", table1, "alice", "앨리스", "s-alice")).isTrue();

        assertThat(locks.acquire("m1", table1, "bob", "밥", "s-bob")).isFalse();
        assertThat(locks.lockedByOther("m1", table1, "bob")).isTrue();
        assertThat(locks.lockedByOther("m1", table1, "alice")).isFalse();
    }

    @Test
    @DisplayName("본인 재획득은 갱신(renew)이다 — 만료 창이 다시 늘어난다")
    void selfReacquireRenewsLastSeen() {
        assertThat(locks.acquire("m1", table1, "alice", "앨리스", "s-alice")).isTrue();
        clock.advance(Duration.ofMinutes(1)); // TTL 2m 중 1m 경과
        assertThat(locks.acquire("m1", table1, "alice", "앨리스", "s-alice")).isTrue();

        clock.advance(Duration.ofMinutes(1).plusMillis(30)); // 갱신 시점부터 다시 잰다 — 아직 산다
        assertThat(locks.lockedByOther("m1", table1, "bob")).isTrue();
    }

    @Test
    @DisplayName("TTL(2m)이 지나면 lazy 만료 — 타인 획득이 통과된다")
    void expiredLockIsLazilySwept() {
        assertThat(locks.acquire("m1", table1, "alice", "앨리스", "s-alice")).isTrue();

        clock.advance(Duration.ofMinutes(2).plusSeconds(1));

        assertThat(locks.lockedByOther("m1", table1, "bob")).isFalse();
        assertThat(locks.acquire("m1", table1, "bob", "밥", "s-bob")).isTrue();
        assertThat(locks.snapshot("m1"))
                .singleElement()
                .satisfies(lock -> assertThat(lock.userId()).isEqualTo("bob"));
    }

    @Test
    @DisplayName("release는 본인 락만 해제한다 — 타인 release는 무효")
    void releaseOnlyOwnLock() {
        locks.acquire("m1", table1, "alice", "앨리스", "s-alice");
        locks.release("m1", table1, "bob");
        assertThat(locks.find("m1", table1)).isPresent();

        locks.release("m1", table1, "alice");
        assertThat(locks.find("m1", table1)).isEmpty();
        assertThat(locks.snapshot("m1")).isEmpty();
    }

    @Test
    @DisplayName("세션 해제는 그 세션의 락만 몰아낸다 — 다른 세션 락은 유지")
    void releaseSessionRemovesOnlyOwnLocks() {
        LockRegistry.LockTarget note = new LockRegistry.LockTarget("note", "n1");
        locks.acquire("m1", table1, "alice", "앨리스", "s-alice");
        locks.acquire("m1", note, "alice", "앨리스", "s-alice");
        locks.acquire("m1", new LockRegistry.LockTarget("table", "t2"), "bob", "밥", "s-bob");

        assertThat(locks.releaseSession("m1", "s-alice")).isEqualTo(2);

        assertThat(locks.find("m1", table1)).isEmpty();
        assertThat(locks.find("m1", note)).isEmpty();
        Optional<LockRegistry.Lock> bobTable = locks.find("m1", new LockRegistry.LockTarget("table", "t2"));
        assertThat(bobTable).isPresent();
        assertThat(bobTable.get().userId()).isEqualTo("bob");
    }

    @Test
    @DisplayName("룸은 격리된다 — 다른 문서의 같은 대상 ID는 독립 락이다")
    void roomsAreIsolated() {
        locks.acquire("m1", table1, "alice", "앨리스", "s-alice");

        assertThat(locks.lockedByOther("m2", table1, "bob")).isFalse();
        assertThat(locks.acquire("m2", table1, "bob", "밥", "s-bob")).isTrue();
        assertThat(locks.find("m1", table1)).isPresent();
        assertThat(locks.find("m2", table1)).isPresent();
    }

    private static CollabProperties defaultProperties() {
        return new CollabProperties(null, null, null, new CollabProperties.Locks(Duration.ofMinutes(2)),
                null);
    }

    /** 시간을 손으로 옮기는 시계 — TTL·재획득 창을 결정적으로 검증한다 */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
