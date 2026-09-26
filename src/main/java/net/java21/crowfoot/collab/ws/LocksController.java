package net.java21.crowfoot.collab.ws;

import jakarta.validation.Valid;
import net.java21.crowfoot.collab.ws.dto.LocksNotice;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

/** STOMP 발행 수신 — /app/models/{modelId}/locks. 획득·해제 갱신은 LocksService */
@Controller
public class LocksController {

    private final LocksService locks;

    public LocksController(LocksService locks) {
        this.locks = locks;
    }

    @MessageMapping("/models/{modelId}/locks")
    public void locks(@DestinationVariable String modelId,
                      @Payload @Valid LocksNotice notice,
                      SimpMessageHeaderAccessor accessor) {
        locks.handle(modelId, accessor.getSessionId(), CommandController.principal(accessor), notice);
    }
}
