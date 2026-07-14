package com.agora.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket chat with EXTERNALIZED cross-node state (the sticky-session
 * alternative): messages publish to Redis pub/sub; every node delivers to its
 * local sockets only. Kill one of two nodes → clients reconnect anywhere and
 * lose nothing but the socket (chaos exp 10). Presence = Redis key with TTL,
 * refreshed on any frame; silence past TTL = offline.
 *
 * Connect: ws://host:8090/ws?user=alice&room=order-123
 * Frame in: {"text":"hi"} — broadcast to the room across all nodes.
 */
@Component
public class ChatHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);
    private static final Duration PRESENCE_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    // room -> (user -> session), this node only
    private final Map<String, Map<String, WebSocketSession>> local = new ConcurrentHashMap<>();

    public ChatHandler(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        var q = params(session);
        local.computeIfAbsent(q.get("room"), r -> new ConcurrentHashMap<>()).put(q.get("user"), session);
        touchPresence(q.get("user"));
        log.info("connected user={} room={}", q.get("user"), q.get("room"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        var q = params(session);
        touchPresence(q.get("user"));
        String text = json.readTree(message.getPayload()).path("text").asText();
        String envelope = json.writeValueAsString(Map.of(
                "room", q.get("room"), "from", q.get("user"), "text", text, "ts", System.currentTimeMillis()));
        redis.convertAndSend("chat:room:" + q.get("room"), envelope); // fan out to ALL nodes
    }

    /** Called from the Redis subscriber: deliver to sockets on THIS node. */
    public void deliverLocal(String envelope) {
        try {
            String room = json.readTree(envelope).path("room").asText();
            var sessions = local.get(room);
            if (sessions == null) return;
            TextMessage frame = new TextMessage(envelope);
            for (var e : sessions.entrySet()) {
                if (e.getValue().isOpen()) e.getValue().sendMessage(frame);
            }
        } catch (Exception e) {
            log.warn("deliver failed: {}", e.toString());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var q = params(session);
        var sessions = local.get(q.get("room"));
        if (sessions != null) sessions.remove(q.get("user"));
    }

    public boolean isOnline(String user) {
        return Boolean.TRUE.equals(redis.hasKey("chat:presence:" + user));
    }

    private void touchPresence(String user) {
        redis.opsForValue().set("chat:presence:" + user, "1", PRESENCE_TTL);
    }

    private static Map<String, String> params(WebSocketSession session) {
        Map<String, String> out = new ConcurrentHashMap<>(Map.of("user", "anon", "room", "lobby"));
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2) out.put(p[0], p[1]);
            }
        }
        return out;
    }
}
