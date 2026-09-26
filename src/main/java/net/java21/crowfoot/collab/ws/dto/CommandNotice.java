package net.java21.crowfoot.collab.ws.dto;

import tools.jackson.databind.JsonNode;

/**
 * 커맨드 발행 — /app/models/{modelId}/commands body.
 * change는 웹의 ErdChange 직렬화 그대로(서버는 검증·중계만 — 병합기 없음).
 */
public record CommandNotice(JsonNode change) {
}
