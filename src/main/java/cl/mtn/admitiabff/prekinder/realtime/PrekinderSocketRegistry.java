package cl.mtn.admitiabff.prekinder.realtime;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

@Component
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderSocketRegistry {
    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final Map<String, String> applicationSessions = new ConcurrentHashMap<>();

    public WebSocketHandler decorate(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sockets.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }
            @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sockets.remove(session.getId()); applicationSessions.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    public void bind(String socketSessionId, String applicationSessionId) {
        if (socketSessionId != null && applicationSessionId != null) applicationSessions.put(socketSessionId, applicationSessionId);
    }

    public void closeApplicationSession(String applicationSessionId) {
        applicationSessions.forEach((socketId, boundSession) -> {
            if (!boundSession.equals(applicationSessionId)) return;
            WebSocketSession socket = sockets.get(socketId);
            try { if (socket != null && socket.isOpen()) socket.close(CloseStatus.POLICY_VIOLATION); }
            catch (IOException ignored) { /* cierre best-effort; el heartbeat termina el socket */ }
        });
    }
}
