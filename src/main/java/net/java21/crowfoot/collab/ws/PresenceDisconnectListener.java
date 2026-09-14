package net.java21.crowfoot.collab.ws;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * 세션 끊김(탭 닫기·네트워크 단절·에러) → 룸 해제 + 퇴장 브로드캐스트.
 * 클라이언트가 leave를 보내지 못하고 사라지는 경우가 대부분이라
 * disconnect 정리가 presence의 실제 원천이다.
 */
@Component
public class PresenceDisconnectListener {

    private final PresenceService presence;

    public PresenceDisconnectListener(PresenceService presence) {
        this.presence = presence;
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        presence.disconnect(event.getSessionId());
    }
}
