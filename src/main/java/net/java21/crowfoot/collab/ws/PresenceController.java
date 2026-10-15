package net.java21.crowfoot.collab.ws;

import jakarta.validation.Valid;
import net.java21.crowfoot.collab.ws.dto.SavedNotice;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Objects;

/**
 * STOMP 발행 수신 — /app/models/{modelId}/join · leave · saved.
 * 신원은 CONNECT에서 AuthGate가 확정한 Principal(모드별 원천 — header·token).
 */
@Controller
public class PresenceController {

    private final PresenceService presence;

    public PresenceController(PresenceService presence) {
        this.presence = presence;
    }

    @MessageMapping("/models/{modelId}/join")
    public void join(@DestinationVariable String modelId, SimpMessageHeaderAccessor accessor) {
        presence.join(modelId, sessionId(accessor), participant(accessor));
    }

    @MessageMapping("/models/{modelId}/leave")
    public void leave(@DestinationVariable String modelId, SimpMessageHeaderAccessor accessor) {
        presence.leave(modelId, sessionId(accessor));
    }

    @MessageMapping("/models/{modelId}/saved")
    public void saved(@DestinationVariable String modelId,
                      @Payload @Valid SavedNotice notice,
                      SimpMessageHeaderAccessor accessor) {
        presence.saved(modelId, notice.version(), participant(accessor));
    }

    /** 인바운드 STOMP 메시지에는 Spring이 세션 ID를 언제나 싣는다 */
    private static String sessionId(SimpMessageHeaderAccessor accessor) {
        return Objects.requireNonNull(accessor.getSessionId(), "STOMP 세션 ID 없음");
    }

    private CollabRooms.Participant participant(SimpMessageHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof StompPrincipal principal) {
            return principal.toParticipant();
        }
        // 인터셉터가 CONNECT를 거부하므로 여기에 도달할 수 없다 — 방어적 폴백
        Objects.requireNonNull(user, "인증되지 않은 STOMP 세션");
        return new CollabRooms.Participant(user.getName(), user.getName(), null, null);
    }
}
