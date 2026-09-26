package net.java21.crowfoot.collab.ws;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 룸 단위 단조 seq 원천 — 커맨드 브로드캐스트와 saved 푸시가 같은 순번 공간을 쓴다
 * (수신 측 "maxReceivedSeq ≥ saved.seq면 baseVersion 승계" 판정의 전제).
 * compute가 키별 원자적이라 부여와 증가가 뒤집히지 않는다. 채팅 seq는 메시지 순서
 * 정렬용의 별도 공간(ChatService) — 이 seq와 섞이지 않는다.
 */
@Component
public class Sequences {

    private final ConcurrentHashMap<String, Long> sequences = new ConcurrentHashMap<>();

    public long next(String modelId) {
        return sequences.compute(modelId, (k, current) -> current == null ? 1L : current + 1);
    }

    /** 현재 seq(마지막 부여값, 없으면 0) — saved 푸시가 번호를 소비하지 않고 참조만 한다 */
    public long current(String modelId) {
        return sequences.getOrDefault(modelId, 0L);
    }
}
