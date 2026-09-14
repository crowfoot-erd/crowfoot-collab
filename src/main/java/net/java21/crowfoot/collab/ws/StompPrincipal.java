package net.java21.crowfoot.collab.ws;

import java.security.Principal;

/**
 * STOMP 세션 신원 — CONNECT 헤더로 승격된 사용자.
 * name()은 표시명(presence 브로드캐스트에 실을 이름), userId는 본래 식별자다.
 */
public record StompPrincipal(String userId, String displayName) implements Principal {

    @Override
    public String getName() {
        return userId;
    }
}
