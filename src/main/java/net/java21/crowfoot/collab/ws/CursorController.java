package net.java21.crowfoot.collab.ws;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import tools.jackson.databind.JsonNode;

/** STOMP 발행 수신 — /app/models/{modelId}/cursor. 본문 {x, y, selection, dragging}을 그대로 넘긴다 */
@Controller
public class CursorController {

    private final CursorService cursors;

    public CursorController(CursorService cursors) {
        this.cursors = cursors;
    }

    @MessageMapping("/models/{modelId}/cursor")
    public void cursor(@DestinationVariable String modelId,
                       @Payload JsonNode cursor,
                       SimpMessageHeaderAccessor accessor) {
        cursors.move(modelId, accessor.getSessionId(), CommandController.principal(accessor), cursor);
    }
}
