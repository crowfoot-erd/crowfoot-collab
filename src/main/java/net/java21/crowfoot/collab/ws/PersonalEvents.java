package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.ws.dto.RejectionEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 요청자 전용 개인 큐 발송 — /user/queue/models/{modelId}/collab-events
 * (user destination — SimpMessagingTemplate.convertAndSendToUser). 브로드캐스트가 아닌
 * "너에게만" 통보: 쓰기 차단(rejected)과 resync 지시.
 */
@Component
public class PersonalEvents {

    private final SimpMessagingTemplate messaging;

    public PersonalEvents(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /** 쓰기 발행 차단 통보 — reason: read-only|lock-held|payload-too-large */
    public void rejected(String modelId, String userId, String reason) {
        messaging.convertAndSendToUser(userId,
                "/queue/models/" + modelId + "/collab-events",
                RejectionEvent.rejected(modelId, reason));
    }

    /** 서버 상태 강제 수화·읽기 전용 전환 지시 */
    public void resync(String modelId, String userId, String reason) {
        messaging.convertAndSendToUser(userId,
                "/queue/models/" + modelId + "/collab-events",
                RejectionEvent.resync(modelId, reason));
    }
}
