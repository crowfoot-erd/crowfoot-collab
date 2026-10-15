package net.java21.crowfoot.collab.client;

import net.java21.crowfoot.collab.config.CollabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * core 내부 API 소비 클라이언트 (05-editor/03-collaboration.md Section 2.1) —
 * introspection이 신원(sub)만 주므로 표시 프로필·룸(=Model) 멤버십은 core에 묻는다.
 *
 * <p>GET /internal/core/collab/models/{id}/membership?userId= → {workspaceId, role},
 * GET /internal/core/collab/users/{id}/profile → {userId, name, avatarUrl, githubLogin}.
 * 캐시는 인스턴스 로컬(멤버십 60s — 쓰기 발행 때마다 호출되지만 실질 조회는 만료 시,
 * 프로필 5분). 장애·5xx는 empty(fail-closed — 호출부는 쓰기 거부·폴백 표시명),
 * 4xx는 확정 부정(멤버십 "NONE")으로 구분한다.
 */
@Component
public class CoreInternalClient {

    private static final Logger log = LoggerFactory.getLogger(CoreInternalClient.class);

    static final Duration MEMBERSHIP_TTL = Duration.ofSeconds(60);
    static final Duration PROFILE_TTL = Duration.ofMinutes(5);

    /** 표시 프로필 — /me의 도출 규칙(GitHub 아바타 계산·핸들 저장값)을 core가 내려준다 */
    public record Profile(String userId, String name, String avatarUrl, String githubLogin) {
    }

    private record CacheEntry(Object value, Instant expiresAt) {
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CoreInternalClient(RestClient.Builder builder,
                              ObjectMapper objectMapper,
                              Clock clock,
                              CollabProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(3_000);
        this.restClient = builder.baseUrl(properties.core().baseUrl())
                .requestFactory(factory)
                .build();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 룸 멤버십 역할 — OWNER/EDITOR/COMMENTER/VIEWER, 비멤버 "NONE".
     *
     * @return 확정 역할. 4xx는 "NONE"(모델 없음·비멤버 — 쓰기 거부가 목적이므로 부정 확정),
     *         전송 실패·5xx·계약 밖은 empty(장애 — 호출부는 read-only 폴백)
     */
    public Optional<String> membership(String modelId, String userId) {
        String key = "membership:" + modelId + ":" + userId;
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now(clock))) {
            return Optional.of((String) cached.value());
        }
        try {
            String body = restClient.get()
                    .uri(b -> b.path("/internal/core/collab/models/{modelId}/membership")
                            .queryParam("userId", userId)
                            .build(modelId))
                    .retrieve()
                    .body(String.class);
            String role = roleOf(body);
            if (role == null) {
                return Optional.empty(); // 계약 밖 — 장애 취급(fail-closed)
            }
            cache.put(key, new CacheEntry(role, Instant.now(clock).plus(MEMBERSHIP_TTL)));
            return Optional.of(role);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                cache.put(key, new CacheEntry("NONE", Instant.now(clock).plus(MEMBERSHIP_TTL)));
                return Optional.of("NONE");
            }
            log.warn("core membership lookup failed: {}", ex.getMessage());
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("core unreachable: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** 표시 프로필 — 없는 사용자(404)·장애는 empty(호출부는 폴백 표시명 사용) */
    public Optional<Profile> profile(String userId) {
        String key = "profile:" + userId;
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now(clock))) {
            return Optional.of((Profile) cached.value());
        }
        try {
            String body = restClient.get()
                    .uri(b -> b.path("/internal/core/collab/users/{userId}/profile").build(userId))
                    .retrieve()
                    .body(String.class);
            JsonNode response = objectMapper.readValue(body, JsonNode.class).path("response");
            if (!response.path("userId").isTextual()) {
                return Optional.empty();
            }
            Profile profile = new Profile(response.path("userId").asText(),
                    textOrNull(response, "name"), textOrNull(response, "avatarUrl"),
                    textOrNull(response, "githubLogin"));
            cache.put(key, new CacheEntry(profile, Instant.now(clock).plus(PROFILE_TTL)));
            return Optional.of(profile);
        } catch (Exception ex) {
            log.warn("core profile lookup failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private String roleOf(String body) {
        JsonNode root = objectMapper.readValue(body, JsonNode.class);
        JsonNode response = root.path("response");
        if (!root.path("header").path("isSuccessful").asBoolean(false)
                || !response.path("role").isTextual()) {
            return null;
        }
        return response.path("role").asText();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
