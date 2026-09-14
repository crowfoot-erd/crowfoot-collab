package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.ws.dto.ModelSavedEvent;
import net.java21.crowfoot.collab.ws.dto.PresenceEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 룸 상태 변경을 브로드캐스트로 이어붙이는 흐름 — 컨트롤러(STOMP 발행)와
 * disconnect 리스너(비자발적 해제)가 같은 경로를 탄다.
 */
@Service
public class PresenceService {

    private final CollabRooms rooms;
    private final SimpMessagingTemplate messaging;

    public PresenceService(CollabRooms rooms, SimpMessagingTemplate messaging) {
        this.rooms = rooms;
        this.messaging = messaging;
    }

    /** 문서 룸 가입 — 세션→룸 역인덱스까지 묶어 등록하고 입장을 알린다 */
    public void join(String modelId, String sessionId, CollabRooms.Participant me) {
        rooms.join(modelId, sessionId, me);
        rooms.bindSession(sessionId, modelId);
        broadcastPresence(modelId, "join", me);
    }

    /** 명시적 퇴장 — 해제된 참가자가 있을 때만 알린다(중복 leave는 조용히) */
    public void leave(String modelId, String sessionId) {
        CollabRooms.Participant left = rooms.leave(modelId, sessionId);
        rooms.unbindSession(sessionId);
        if (left != null) {
            broadcastPresence(modelId, "leave", left);
        }
    }

    /** 세션 끊김 — 역인덱스로 룸을 찾아 leave와 동일하게 정리한다 */
    public void disconnect(String sessionId) {
        String modelId = rooms.unbindSession(sessionId);
        if (modelId == null) {
            return; // join 없이 끊긴 세션(구독만 하고 발행 안 한 뷰어)
        }
        CollabRooms.Participant left = rooms.leave(modelId, sessionId);
        if (left != null) {
            broadcastPresence(modelId, "leave", left);
        }
    }

    /** 저장 변경 푸시 — 본문 없이 버전과 저장자만. 수신 측이 상세 조회로 뒤따른다 */
    public void saved(String modelId, int version, CollabRooms.Participant by) {
        messaging.convertAndSend("/topic/models/" + modelId + "/version",
                new ModelSavedEvent(modelId, version, by.userId(), by.name(), Instant.now()));
    }

    private void broadcastPresence(String modelId, String type, CollabRooms.Participant changed) {
        messaging.convertAndSend("/topic/models/" + modelId + "/presence",
                new PresenceEvent(type, changed.userId(), changed.name(), Instant.now(),
                        rooms.participants(modelId)));
    }
}
