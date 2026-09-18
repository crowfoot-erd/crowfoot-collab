package net.java21.crowfoot.collab.ws;

import java.security.Principal;

/**
 * STOMP 세션 신원 — CONNECT 헤더로 승격된 사용자.
 * name()은 표시명(presence 브로드캐스트에 실을 이름), userId는 본래 식별자다.
 * avatarUrl·userLogin은 선택(없는 제공자는 null — 웹이 이니셜·@생략으로 폴백).
 */
public record StompPrincipal(String userId, String displayName, String avatarUrl, String userLogin) implements Principal {

    @Override
    public String getName() {
        return userId;
    }

    /** 룸 참가자 값 객체로 변환 — 발행 컨트롤러(presence·chat)가 같은 신원을 쓴다 */
    public CollabRooms.Participant toParticipant() {
        return new CollabRooms.Participant(userId, displayName, avatarUrl, userLogin);
    }
}
