package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.ws.dto.CommandEvent;
import net.java21.crowfoot.collab.ws.dto.CommandNotice;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * 커맨드 채널 흐름 (05-editor/03-collaboration.md Section 2.1) — 검증·seq 부여·중계만.
 * 병합기 없음: 수렴은 전 클라이언트가 같은 "서버 seq 순서" 규칙으로 각자 적용해 달성한다.
 *
 * <p>차단 순서: 역할(read-only → 개인 큐 거부) → 락(타인 선점 대상 → 거부) →
 * 본문 검증(형식·상한 — chat의 조용히 무시 관례와 달리 발신자에게 거부 사실을 알린다).
 */
@Service
public class CommandService {

    /** 커맨드 1건 상한 — 단일 ErdChange는 수 KB 이하다(스냅샷 전체가 아님) */
    static final int MAX_CHANGE_CHARS = 64 * 1024;

    private final SimpMessagingTemplate messaging;
    private final Sequences sequences;
    private final RoomAuthorizer authorizer;
    private final LockRegistry locks;
    private final CollabRooms rooms;
    private final PersonalEvents events;

    public CommandService(SimpMessagingTemplate messaging, Sequences sequences,
                          RoomAuthorizer authorizer, LockRegistry locks, CollabRooms rooms,
                          PersonalEvents events) {
        this.messaging = messaging;
        this.sequences = sequences;
        this.authorizer = authorizer;
        this.locks = locks;
        this.rooms = rooms;
        this.events = events;
    }

    public void publish(String modelId, String sessionId, StompPrincipal actor, CommandNotice notice) {
        JsonNode change = notice == null ? null : notice.change();
        if (change == null || !change.isObject() || change.path("type").asText("").isBlank()) {
            return; // 형식 불량 — 조용히 무시(chat 관례)
        }

        // 역할 — 쓰기 직전 재검증(캐시 만료분은 재조회). 스냅샷 표시도 최신 role로 갱신한다
        String role = authorizer.currentRole(modelId, actor.userId());
        rooms.updateRole(modelId, sessionId, role);
        if (!RoomAuthorizer.canWrite(role)) {
            events.rejected(modelId, actor.userId(), "read-only");
            return;
        }

        // 락 2차 방어 — 타인이 잠근 구조 대상의 변경은 거부(사전 방지 1차는 클라이언트)
        Optional<LockRegistry.LockTarget> target = ChangeTargets.of(change);
        if (target.isPresent() && locks.lockedByOther(modelId, target.get(), actor.userId())) {
            events.rejected(modelId, actor.userId(), "locked");
            return;
        }

        if (change.toString().length() > MAX_CHANGE_CHARS) {
            events.rejected(modelId, actor.userId(), "payload-too-large");
            return;
        }

        long seq = sequences.next(modelId);
        messaging.convertAndSend("/topic/models/" + modelId + "/commands",
                new CommandEvent(seq, actor.userId(), actor.displayName(), change));
    }
}
