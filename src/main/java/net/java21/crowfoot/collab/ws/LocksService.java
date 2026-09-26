package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.ws.dto.LocksEvent;
import net.java21.crowfoot.collab.ws.dto.LocksNotice;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Edit Session Lock 흐름 — acquire·release·renew을 받아 전체 스냅샷을 브로드캐스트한다.
 * renew는 본인 acquire와 같은 경로(갱신 = lastSeen touch)다. 락도 쓰기라 인가 게이트를
 * 통과한다(read-only 사용자의 락 발행은 거부 — "쓰기 채널 자체를 열어주지 않는다").
 */
@Service
public class LocksService {

    private final SimpMessagingTemplate messaging;
    private final LockRegistry locks;
    private final RoomAuthorizer authorizer;
    private final CollabRooms rooms;
    private final PersonalEvents events;

    public LocksService(SimpMessagingTemplate messaging, LockRegistry locks,
                        RoomAuthorizer authorizer, CollabRooms rooms, PersonalEvents events) {
        this.messaging = messaging;
        this.locks = locks;
        this.authorizer = authorizer;
        this.rooms = rooms;
        this.events = events;
    }

    public void handle(String modelId, String sessionId, StompPrincipal actor, LocksNotice notice) {
        if (notice == null) {
            return;
        }

        String role = authorizer.currentRole(modelId, actor.userId());
        rooms.updateRole(modelId, sessionId, role);
        if (!RoomAuthorizer.canWrite(role)) {
            events.rejected(modelId, actor.userId(), "read-only");
            return;
        }

        LockRegistry.LockTarget target =
                new LockRegistry.LockTarget(notice.targetType(), notice.targetId());
        switch (notice.action()) {
            case "acquire", "renew" -> {
                if (locks.acquire(modelId, target, actor.userId(), actor.displayName(), sessionId)) {
                    broadcast(modelId);
                } else {
                    events.rejected(modelId, actor.userId(), "lock-held");
                }
            }
            case "release" -> {
                locks.release(modelId, target, actor.userId());
                broadcast(modelId);
            }
            default -> {
                // 알 수 없는 action — 조용히 무시
            }
        }
    }

    /** 세션 해제(disconnect 원천) — 남은 락이 있으면 스냅샷으로 알린다 */
    public void releaseSession(String modelId, String sessionId) {
        if (locks.releaseSession(modelId, sessionId) > 0) {
            broadcast(modelId);
        }
    }

    /** 미가입 세션 방어 — join 없이 잡은 락을 전 룸 스윕으로 풀고 알린다 */
    public void releaseSessionAnywhere(String sessionId) {
        for (String modelId : locks.releaseSessionAnywhere(sessionId)) {
            broadcast(modelId);
        }
    }

    /** 늦게 들어온 참가자를 위한 현재 락 상태 발포 — join이 부른다(빈 룸은 조용히) */
    public void broadcastSnapshot(String modelId) {
        if (!locks.snapshot(modelId).isEmpty()) {
            broadcast(modelId);
        }
    }

    private void broadcast(String modelId) {
        List<LocksEvent.LockView> views = locks.snapshot(modelId).stream()
                .map(lock -> new LocksEvent.LockView(lock.targetType(), lock.targetId(),
                        lock.userId(), lock.userName(), lock.acquiredAt()))
                .toList();
        messaging.convertAndSend("/topic/models/" + modelId + "/locks", new LocksEvent(views));
    }
}
