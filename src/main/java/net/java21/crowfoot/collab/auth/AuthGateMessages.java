package net.java21.crowfoot.collab.auth;

import java.util.Map;

/** CONNECT 거부 문구(4언어) — AuthGate가 던지는 IllegalArgumentException 메시지 원천 */
final class AuthGateMessages {

    /** header 모드 — X-USER-ID 없는 CONNECT */
    private static final Map<String, String> MISSING_USER_ID_MESSAGES = Map.of(
            "ko", "X-USER-ID 헤더가 필요합니다",
            "en", "The X-USER-ID header is required",
            "ja", "X-USER-IDヘッダーが必要です",
            "zh", "需要 X-USER-ID 请求头");

    private AuthGateMessages() {
    }

    static String missingUserId(String lang) {
        return MISSING_USER_ID_MESSAGES.get(lang);
    }
}
