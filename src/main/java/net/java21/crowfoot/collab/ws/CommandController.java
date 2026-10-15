package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.ws.dto.CommandNotice;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Objects;

/** STOMP 발행 수신 — /app/models/{modelId}/commands. 검증·중계는 CommandService */
@Controller
public class CommandController {

    private final CommandService commands;

    public CommandController(CommandService commands) {
        this.commands = commands;
    }

    @MessageMapping("/models/{modelId}/commands")
    public void commands(@DestinationVariable String modelId,
                         @Payload CommandNotice notice,
                         SimpMessageHeaderAccessor accessor) {
        commands.publish(modelId, accessor.getSessionId(), principal(accessor), notice);
    }

    static StompPrincipal principal(SimpMessageHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof StompPrincipal principal) {
            return principal;
        }
        // 인터셉터가 CONNECT를 거부하므로 여기에 도달할 수 없다 — 방어적 폴백
        Objects.requireNonNull(user, "인증되지 않은 STOMP 세션");
        return new StompPrincipal(user.getName(), user.getName(), null, null);
    }
}
