package net.java21.crowfoot.collab.auth;

import net.java21.crowfoot.collab.config.CollabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 인증 서버 Introspection 소비 클라이언트 (02-auth/requirements.md Section 1.6·1.7) —
 * gateway {@code IntrospectionClient}의 servlet(RestClient) 이식. CONNECT 확립 스레드에서
 * 호출되므로 짧은 타임아웃(연결 2s·읽기 3s)으로 블로킹을 제한한다.
 *
 * <p>POST {auth-base-url}/internal/auth/introspect — form-urlencoded {@code token={Access JWT}}.
 * 무효(active=false)는 정상 응답이고, 전송 실패·타임아웃·5xx·계약 밖 본문은 전부
 * "검증 실패"(empty)로 정규화한다 — <b>fail-closed</b>(게이트웨이 원칙과 동일).
 * 캐시는 토큰 원문의 SHA-256 키, 만료 min(now+TTL, 토큰 exp), positive(active) 결과만 저장
 * (gateway TokenValidationCache 관례 이식).
 */
@Component
public class AuthIntrospectionClient {

    private static final Logger log = LoggerFactory.getLogger(AuthIntrospectionClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final CollabProperties properties;
    private final ConcurrentMap<String, CachedSubject> cache = new ConcurrentHashMap<>();

    public AuthIntrospectionClient(RestClient.Builder builder,
                                   ObjectMapper objectMapper,
                                   Clock clock,
                                   CollabProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(3_000);
        this.restClient = builder.baseUrl(properties.auth().authBaseUrl())
                .requestFactory(factory)
                .build();
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
    }

    /** @return 토큰의 사용자 식별자(sub). 무효·장애·계약 밖이면 empty — 호출부는 연결을 거부한다 */
    public Optional<String> subject(String token) {
        String key = sha256(token);
        CachedSubject cached = cache.get(key);
        if (cached != null && cached.expiresAtMillis() > clock.millis()) {
            return Optional.of(cached.sub());
        }
        try {
            String body = restClient.post()
                    .uri("/internal/auth/introspect")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(new LinkedMultiValueMap<>(Map.of("token", List.of(token))))
                    .retrieve()
                    .body(String.class);
            return parse(body, key);
        } catch (Exception ex) {
            log.warn("introspection unavailable: {}", ex.getMessage());
            return Optional.empty(); // fail-closed — 장애를 "무효"와 같게 취급해 연결을 끊는다
        }
    }

    /** 계약 밖 본문도 장애 취급(fail-closed) — active면 sub가 반드시 있다 */
    private Optional<String> parse(String body, String cacheKey) {
        JsonNode root = objectMapper.readValue(body, JsonNode.class);
        JsonNode header = root.path("header");
        JsonNode response = root.path("response");
        boolean active = header.path("isSuccessful").asBoolean(false)
                && response.path("active").asBoolean(false);
        String sub = response.path("sub").asText(null);
        if (!active || sub == null || sub.isBlank()) {
            return Optional.empty();
        }
        // 웹 로그인 토큰(typ ACCESS)만 받는다 — 워크스페이스 액세스 토큰(MCP용)으로는 협업 채널에 접속할 수 없다
        // (05-editor/03-collaboration.md Section 2.1). typ이 없는 응답은 예전 인증 서버의 것이므로 ACCESS로 본다
        String typ = response.path("typ").asText(null);
        if (typ != null && !"ACCESS".equals(typ)) {
            return Optional.empty();
        }
        long expiresAt = clock.millis() + properties.auth().tokenCacheTtl().toMillis();
        Long exp = response.path("exp").isNumber() ? response.path("exp").asLong() : null;
        if (exp != null) {
            expiresAt = Math.min(expiresAt, exp * 1000); // 캐시가 토큰 수명보다 오래 사는 것을 막는다
        }
        cache.put(cacheKey, new CachedSubject(sub, expiresAt));
        return Optional.of(sub);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 미지원 환경", ex);
        }
    }

    private record CachedSubject(String sub, long expiresAtMillis) {
    }
}
