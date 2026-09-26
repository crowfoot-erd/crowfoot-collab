package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.config.CollabProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Edit Session Lock 저장소 (05-editor/03-collaboration.md Section 2.1) — 인메모리, 세션 수명.
 *
 * <p>단위는 table(컬럼·PK·UK·인덱스 등 테이블 구성 포함)·relationship·note·area.
 * TTL은 무갱신 만료(lazy — 조회 시점에 비·만료분 제거)의 실패 안전망일 뿐, 해제의
 * 원천은 다이얼로그 종료(release)와 세션 disconnect(releaseSession)이다.
 * presence와 같은 멱등 교체 철학이라 이벤트는 언제나 전체 스냅샷으로 브로드캐스트한다.
 */
@Component
public class LockRegistry {

    /** 락 대상 — targetType은 table|relationship|note|area(웹 커맨드 type 접두사와 같은 어휘) */
    public record LockTarget(String targetType, String targetId) {
        public String key() {
            return targetType + ":" + targetId;
        }
    }

    public record Lock(String targetType, String targetId, String userId, String userName,
                       String sessionId, Instant acquiredAt, Instant lastSeenAt) {
    }

    private final Map<String, ConcurrentHashMap<String, Lock>> locks = new ConcurrentHashMap<>();
    private final CollabProperties properties;
    private final Clock clock;

    public LockRegistry(CollabProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 획득(또는 본인 재획득 = lastSeen 갱신). 남의 만료 전 락이 있으면 실패한다 */
    public boolean acquire(String modelId, LockTarget target, String userId, String userName,
                           String sessionId) {
        Instant now = Instant.now(clock);
        ConcurrentHashMap<String, Lock> room = locks.computeIfAbsent(modelId, k -> new ConcurrentHashMap<>());
        boolean[] acquired = {false};
        room.compute(target.key(), (k, existing) -> {
            if (existing != null && !expired(existing, now)) {
                if (!existing.userId().equals(userId)) {
                    return existing; // 타인 선점 — 획득 실패
                }
                acquired[0] = true;
                return new Lock(existing.targetType(), existing.targetId(), existing.userId(),
                        existing.userName(), sessionId, existing.acquiredAt(), now);
            }
            acquired[0] = true;
            return new Lock(target.targetType(), target.targetId(), userId, userName, sessionId, now, now);
        });
        return acquired[0];
    }

    /** 본인 락만 해제한다 */
    public void release(String modelId, LockTarget target, String userId) {
        ConcurrentHashMap<String, Lock> room = locks.get(modelId);
        if (room == null) {
            return;
        }
        room.computeIfPresent(target.key(), (k, existing) -> existing.userId().equals(userId) ? null : existing);
        removeRoomIfEmpty(modelId, room);
    }

    /** 커맨드 2차 방어 — 이 대상이 타인의 만료 전 락으로 잠겨 있는가 */
    public boolean lockedByOther(String modelId, LockTarget target, String userId) {
        ConcurrentHashMap<String, Lock> room = locks.get(modelId);
        if (room == null) {
            return false;
        }
        Lock existing = room.get(target.key());
        return existing != null && !expired(existing, Instant.now(clock))
                && !existing.userId().equals(userId);
    }

    /** 세션 해제(disconnect 원천) — 그 세션이 보유했던 락 수를 반환한다 */
    public int releaseSession(String modelId, String sessionId) {
        ConcurrentHashMap<String, Lock> room = locks.get(modelId);
        if (room == null) {
            return 0;
        }
        int before = room.size();
        room.values().removeIf(lock -> lock.sessionId().equals(sessionId));
        int released = before - room.size();
        removeRoomIfEmpty(modelId, room);
        return released;
    }

    /** 미가입 세션 방어 — join 없이 락만 잡고 사라진 세션을 전 룸 스윕으로 정리한다.
     *  정리가 일어난 룸 ID 목록을 반환(호출부가 빈 스냅샷을 브로드캐스트한다). */
    public List<String> releaseSessionAnywhere(String sessionId) {
        List<String> touched = new ArrayList<>();
        locks.forEach((modelId, room) -> {
            if (room.values().removeIf(lock -> lock.sessionId().equals(sessionId))) {
                removeRoomIfEmpty(modelId, room);
                touched.add(modelId);
            }
        });
        return touched;
    }

    /** 전체 락 스냅샷 — 만료분을 밀어내고 남은 확정 락만 */
    public List<Lock> snapshot(String modelId) {
        ConcurrentHashMap<String, Lock> room = locks.get(modelId);
        if (room == null) {
            return List.of();
        }
        Instant now = Instant.now(clock);
        room.values().removeIf(lock -> expired(lock, now));
        removeRoomIfEmpty(modelId, room);
        return new ArrayList<>(room.values());
    }

    /** 대상 조회(테스트·디버그) — Optional 감싼 단일 락 */
    public Optional<Lock> find(String modelId, LockTarget target) {
        ConcurrentHashMap<String, Lock> room = locks.get(modelId);
        return room == null ? Optional.empty() : Optional.ofNullable(room.get(target.key()));
    }

    private boolean expired(Lock lock, Instant now) {
        return lock.lastSeenAt().plus(properties.locks().ttl()).isBefore(now);
    }

    private void removeRoomIfEmpty(String modelId, ConcurrentHashMap<String, Lock> room) {
        if (room.isEmpty()) {
            locks.remove(modelId, room);
        }
    }
}
