package net.java21.crowfoot.collab.ws;

import jakarta.validation.Valid;
import net.java21.crowfoot.collab.ws.dto.SavedNotice;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * STOMP 발행 수신 — /app/models/{modelId}/join · leave · saved.
 * 신원은 CONNECT에서 승격된 Principal(헤더 위조는 로컬 계약 — 운영은 게이트웨이 주입).
 */
@Controller
public class PresenceController {

    private final PresenceService presence;

    public PresenceController(PresenceService presence) {
        this.presence = presence;
    }

    @MessageMapping("/models/{modelId}/join")
    public void join(@DestinationVariable String modelId, SimpMessageHeaderAccessor accessor) {
        presence.join(modelId, accessor.getSessionId(), participant(accessor));
    }

    @MessageMapping("/models/{modelId}/leave")
    public void leave(@DestinationVariable String modelId, SimpMessageHeaderAccessor accessor) {
        presence.leave(modelId, accessor.getSessionId());
    }

    @MessageMapping("/models/{modelId}/saved")
    public void saved(@DestinationVariable String modelId,
                      @Payload @Valid SavedNotice notice,
                      SimpMessageHeaderAccessor accessor) {
        presence.saved(modelId, notice.version(), participant(accessor));
    }

    private CollabRooms.Participant participant(SimpMessageHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof StompPrincipal principal) {
            return new CollabRooms.Participant(principal.userId(), principal.displayName());
        }
        // 인터셉터가 CONNECT를 거부하므로 여기에 도달할 수 없다 — 방어적 폴백
        return new CollabRooms.Participant(user.getName(), user.getName());
    }
}
