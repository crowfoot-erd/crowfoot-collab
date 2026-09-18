package net.java21.crowfoot.collab.ws;

import jakarta.validation.Valid;
import net.java21.crowfoot.collab.ws.dto.ChatNotice;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * STOMP 발행 수신 — /app/models/{modelId}/chat.
 * 신원은 CONNECT에서 승격된 Principal(다른 발행 엔드포인트와 동일).
 */
@Controller
public class ChatController {

    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    @MessageMapping("/models/{modelId}/chat")
    public void chat(@DestinationVariable String modelId,
                     @Payload @Valid ChatNotice notice,
                     SimpMessageHeaderAccessor accessor) {
        chat.send(modelId, participant(accessor), notice.message());
    }

    private CollabRooms.Participant participant(SimpMessageHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof StompPrincipal principal) {
            return principal.toParticipant();
        }
        // 인터셉터가 CONNECT를 거부하므로 여기에 도달할 수 없다 — 방어적 폴백
        return new CollabRooms.Participant(user.getName(), user.getName(), null, null);
    }
}
