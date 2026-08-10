package cl.mtn.admitiabff.prekinder.realtime;

import cl.mtn.admitiabff.prekinder.domain.PrekinderActor;
import cl.mtn.admitiabff.prekinder.security.PrekinderAuthContext;
import cl.mtn.admitiabff.prekinder.service.PrekinderCommentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderOperationHandler {
    private final PrekinderCommentService comments;
    private final PrekinderPresenceRegistry presence;
    private final PrekinderEventFanout fanout;
    private final SimpMessagingTemplate messaging;

    public PrekinderOperationHandler(PrekinderCommentService comments, PrekinderPresenceRegistry presence,
                                     PrekinderEventFanout fanout, SimpMessagingTemplate messaging) {
        this.comments = comments; this.presence = presence; this.fanout = fanout; this.messaging = messaging;
    }

    @MessageMapping("/prekinder/operations")
    public void operation(@Valid @Payload Operation operation, Principal principal,
                          @Header("simpSessionAttributes") Map<String, Object> session) {
        var binding = (RealtimeTicketService.TicketBinding) session.get("prekinderBinding");
        if (binding == null || principal == null || !principal.getName().equals(binding.actorId())) {
            throw new SecurityException("Sesión de socket inválida");
        }
        PrekinderAuthContext.set(new PrekinderAuthContext.Principal(
            new PrekinderActor(UUID.fromString(binding.actorId()), 0L, binding.role()),
            binding.subject(), null, binding.sessionId()));
        try {
            if (operation.type() == OperationType.WATCH_EVALUATION) {
                presence.watch(require(operation.evaluationId(), "evaluationId"), binding.actorId());
                ack(principal.getName(), operation.operationId(), "WATCHING", null, false);
                return;
            }
            if (operation.type() == OperationType.WATCH_ACTOR) {
                UUID actorId = require(operation.actorId(), "actorId");
                if (!actorId.toString().equals(binding.actorId())) {
                    throw new SecurityException("Sólo puedes observar tu propia jornada");
                }
                presence.watch(actorId, binding.actorId());
                ack(principal.getName(), operation.operationId(), "WATCHING", null, false);
                return;
            }
            if (operation.type() == OperationType.WATCH_PROCESS) {
                UUID processId = require(operation.processId(), "processId");
                if (!java.util.Set.of("ADMIN", "COORDINATOR", "CYCLE_DIRECTOR", "PK_ADMIN", "PK_COORDINATOR", "PK_REVIEWER")
                    .contains(binding.role())) {
                    throw new SecurityException("Permiso operativo requerido para observar el proceso");
                }
                presence.watch(processId, binding.actorId());
                ack(principal.getName(), operation.operationId(), "WATCHING", null, false);
                return;
            }
            PrekinderCommentService.MutationResult result = switch (operation.type()) {
                case COMMENT_CREATE -> comments.create(require(operation.evaluationId(), "evaluationId"), operation.operationId(),
                    content(operation.content()), operation.operationId().toString());
                case COMMENT_REVISE -> comments.revise(require(operation.commentId(), "commentId"), operation.operationId(),
                    revision(operation.baseRevision()), content(operation.content()), operation.operationId().toString());
                case COMMENT_TOMBSTONE -> comments.tombstone(require(operation.commentId(), "commentId"), operation.operationId(),
                    revision(operation.baseRevision()), operation.operationId().toString());
                default -> throw new IllegalArgumentException("Operación no permitida");
            };
            // El servicio retorna únicamente después del COMMIT.
            String ackStatus = "COMMITTED";
            if (!result.duplicate() && result.eventId() != null) {
                try {
                    fanout.publish(new PrekinderEventFanout.MinimalEvent(result.eventId(), result.comment().evaluationId(),
                        result.comment().serverSequence(), eventType(operation.type())));
                } catch (org.springframework.data.redis.RedisConnectionFailureException exception) {
                    ackStatus = "COMMITTED_DEGRADED";
                }
            }
            ack(principal.getName(), operation.operationId(), ackStatus, result.comment(), result.duplicate());
        } finally {
            PrekinderAuthContext.clear();
        }
    }

    private void ack(String actorId, UUID operationId, String status, Object result, boolean duplicate) {
        messaging.convertAndSendToUser(actorId, "/queue/prekinder/acks",
            Map.of("operationId", operationId, "status", status, "duplicate", duplicate,
                "result", result == null ? Map.of() : result));
    }

    private static String eventType(OperationType type) {
        return switch (type) {
            case COMMENT_CREATE -> "COMMENT_CREATED";
            case COMMENT_REVISE -> "COMMENT_REVISED";
            case COMMENT_TOMBSTONE -> "COMMENT_TOMBSTONED";
            default -> "SYNC_REQUIRED";
        };
    }

    private static String content(String content) {
        if (content == null || content.isBlank() || content.length() > 8000 || content.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Contenido inválido");
        }
        if (content.matches("(?is).*<\\s*(script|iframe|object|embed)[^>]*>.*")) {
            throw new IllegalArgumentException("HTML no permitido");
        }
        return content;
    }
    private static int revision(Integer revision) {
        if (revision == null || revision < 1) throw new IllegalArgumentException("Revisión inválida");
        return revision;
    }
    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " es obligatorio");
        return value;
    }

    public record Operation(
        @NotNull UUID operationId,
        @NotNull OperationType type,
        UUID evaluationId,
        UUID actorId,
        UUID processId,
        UUID commentId,
        @Min(1) Integer baseRevision,
        @Size(max = 8000) String content,
        @Min(0) @Max(Long.MAX_VALUE) Long clientSequence
    ) {}
    public enum OperationType { WATCH_EVALUATION, WATCH_ACTOR, WATCH_PROCESS, COMMENT_CREATE, COMMENT_REVISE, COMMENT_TOMBSTONE }
}
