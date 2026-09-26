package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.ws.dto.ModelSavedEvent;
import net.java21.crowfoot.collab.ws.dto.PresenceEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 룸 상태 변경을 브로드캐스트로 이어붙이는 흐름 — 컨트롤러(STOMP 발행)와
 * disconnect 리스너(비자발적 해제)가 같은 경로를 탄다.
 *
 * <p>join은 룸 멤버십 흐름의 소유자다: 역할 확정(RoomAuthorizer — Viewer/Commenter는
 * read-only 통보), 채팅 기록, 커서·락 현재 스냅샷까지 이어붙인다. leave·disconnect는
 * 그 세션이 가진 일시적 상태(락·커서)까지 함께 정리한다 — disconnect가 해제의 원천.
 */
@Service
public class PresenceService {

    private final CollabRooms rooms;
    private final SimpMessagingTemplate messaging;
    private final ChatService chat;
    private final RoomAuthorizer authorizer;
    private final CursorService cursors;
    private final LocksService locks;
    private final PersonalEvents events;
    private final Sequences sequences;

    public PresenceService(CollabRooms rooms, SimpMessagingTemplate messaging, ChatService chat,
                           RoomAuthorizer authorizer, CursorService cursors, LocksService locks,
                           PersonalEvents events, Sequences sequences) {
        this.rooms = rooms;
        this.messaging = messaging;
        this.chat = chat;
        this.authorizer = authorizer;
        this.cursors = cursors;
        this.locks = locks;
        this.events = events;
        this.sequences = sequences;
    }

    /** 문서 룸 가입 — 세션→룸 역인덱스까지 묶어 등록하고 입장을 알린다.
     *  채팅 기록·커서·락 스냅샷으로 늦게 들어온 팀원의 화면을 현재 상태로 맞추고,
     *  read-only 역할이면 개인 큐로 resync를 보내 웹이 편집 UI를 스스로 잠그게 한다. */
    public void join(String modelId, String sessionId, CollabRooms.Participant me) {
        String role = authorizer.currentRole(modelId, me.userId());
        CollabRooms.Participant participant = me.withRole(role);
        rooms.join(modelId, sessionId, participant);
        rooms.bindSession(sessionId, modelId);
        broadcastPresence(modelId, "join", participant);
        if (chat.hasHistory(modelId)) {
            chat.broadcastHistory(modelId);
        }
        cursors.broadcastSnapshot(modelId);
        locks.broadcastSnapshot(modelId);
        if (!RoomAuthorizer.canWrite(role)) {
            events.resync(modelId, participant.userId(), "read-only");
        }
    }

    /** 명시적 퇴장 — 해제된 참가자가 있을 때만 알린다(중복 leave는 조용히).
     *  락·커서는 참가 여부와 무관하게 세션 단위로 항상 정리한다. */
    public void leave(String modelId, String sessionId) {
        CollabRooms.Participant left = rooms.leave(modelId, sessionId);
        rooms.unbindSession(sessionId);
        locks.releaseSession(modelId, sessionId);
        cursors.removeSession(modelId, sessionId);
        if (left != null) {
            broadcastPresence(modelId, "leave", left);
        }
    }

    /** 세션 끊김 — 역인덱스로 룸을 찾아 leave와 동일하게 정리한다.
     *  join 없이 발행만 한 세션도 방어적으로 스윕한다(락 잔존을 TTL에 맡기지 않는다). */
    public void disconnect(String sessionId) {
        String modelId = rooms.unbindSession(sessionId);
        if (modelId == null) {
            locks.releaseSessionAnywhere(sessionId);
            cursors.removeSessionAnywhere(sessionId);
            return;
        }
        leave(modelId, sessionId);
    }

    /** 저장 변경 푸시 — 본문 없이 버전과 저장자만. 수신 측이 상세 조회로 뒤따른다.
     *  seq는 저장 시점의 룸 현재값(소비 없음) — 수신 측 baseVersion 승계 판정에 쓴다. */
    public void saved(String modelId, int version, CollabRooms.Participant by) {
        messaging.convertAndSend("/topic/models/" + modelId + "/version",
                new ModelSavedEvent(modelId, version, by.userId(), by.name(), Instant.now(),
                        sequences.current(modelId)));
    }

    private void broadcastPresence(String modelId, String type, CollabRooms.Participant changed) {
        messaging.convertAndSend("/topic/models/" + modelId + "/presence",
                new PresenceEvent(type, changed.userId(), changed.name(), Instant.now(),
                        rooms.participants(modelId)));
    }
}
