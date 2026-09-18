package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.ws.dto.ChatEvent;
import net.java21.crowfoot.collab.ws.dto.ChatMessageEntry;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 문서별 채팅 — 기록 보관과 브로드캐스트.
 *
 * <p>기록은 프로세스 수명 인메모리(요구사항 — 재시작 시 초기화, DB 없음). {@link CollabRooms}는
 * 룸이 비면 제거되므로 기록을 거기에 두면 "모두 나간 뒤 재입장한 팀원이 최근 대화를 본다"가
 * 깨진다 — 룸 수명과 분리해 여기서 보관한다. 룸 100개 상한(LRU)으로 장기 프로세스의
 * 무한 성장만 막는다. 메시지 빈도가 낮아 메서드 단위 synchronized로 충분하다.
 */
@Service
public class ChatService {

    static final int HISTORY_LIMIT = 50; // 룸당 최근 발언 창
    static final int ROOMS_LIMIT = 100;  // 기억하는 문서 룸 상한

    private final SimpMessagingTemplate messaging;
    private final LinkedHashMap<String, Deque<ChatMessageEntry>> history = new LinkedHashMap<>();
    private long seq; // 단조 순번 — synchronized 블록에서만 부여·증가

    public ChatService(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /** 채팅 발행 — 기록 창을 갱신하고 룸 전체에 즉시 전파한다(발신자 에코 포함) */
    public synchronized void send(String modelId, CollabRooms.Participant from, String message) {
        ChatMessageEntry entry = new ChatMessageEntry(from.userId(), from.name(), from.avatarUrl(),
                from.userLogin(), message, Instant.now(), ++seq);
        Deque<ChatMessageEntry> window = history.remove(modelId);
        if (window == null) {
            window = new ArrayDeque<>();
        }
        window.addLast(entry); // seq 오름차순 유지(부여와 삽입이 같은 락 안에서 일어난다)
        while (window.size() > HISTORY_LIMIT) {
            window.removeFirst();
        }
        history.put(modelId, window); // 재삽입 — 최근에 쓴 룸이 상한 초과 때 밀려나지 않게 한다
        while (history.size() > ROOMS_LIMIT) {
            history.remove(history.keySet().iterator().next());
        }
        messaging.convertAndSend("/topic/models/" + modelId + "/chat",
                new ChatEvent("message", modelId, entry.userId(), entry.name(), entry.avatarUrl(),
                        entry.userLogin(), entry.message(), entry.at(), entry.seq(), null));
    }

    public synchronized boolean hasHistory(String modelId) {
        Deque<ChatMessageEntry> window = history.get(modelId);
        return window != null && !window.isEmpty();
    }

    /** 최근 대화 창 브로드캐스트 — join 시점. 수신 측은 목록을 이 창으로 교체한다(멱등) */
    public synchronized void broadcastHistory(String modelId) {
        Deque<ChatMessageEntry> window = history.get(modelId);
        if (window == null || window.isEmpty()) {
            return;
        }
        messaging.convertAndSend("/topic/models/" + modelId + "/chat",
                new ChatEvent("history", modelId, null, null, null, null, null, null, 0, List.copyOf(window)));
    }
}
