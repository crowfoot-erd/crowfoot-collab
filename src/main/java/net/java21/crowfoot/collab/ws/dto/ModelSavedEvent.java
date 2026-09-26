package net.java21.crowfoot.collab.ws.dto;

import java.time.Instant;

/**
 * 저장 변경 푸시 — /topic/models/{modelId}/version.
 * 브로드캐스트는 룸 전체에 가지만 savedBy를 실어 클라이언트가
 * 자기 저장 반향은 무시하도록 한다(심플 브로커는 세션 제외 발송을 지원하지 않는다).
 * 본문은 푸시하지 않는다 — 수신 측이 기존 core-api 상세 조회(v1 자동 동기화 경로)로 가져간다.
 * seq는 저장 시점의 룸 seq(Sequences — 커맨드와 같은 공간)다: 수신 측이 받은 커맨드 최종
 * seq ≥ event.seq면 저장 내용을 이미 커맨드로 전부 가지고 있어 baseVersion을 조용히 승계한다.
 */
public record ModelSavedEvent(
        String modelId,
        int version,
        String savedBy,
        String savedByName,
        Instant at,
        long seq) {
}
