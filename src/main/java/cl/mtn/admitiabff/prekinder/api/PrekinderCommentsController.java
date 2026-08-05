package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderCommentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prekinder")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderCommentsController {
    private final PrekinderCommentService comments;
    public PrekinderCommentsController(PrekinderCommentService comments) { this.comments = comments; }

    @GetMapping("/evaluations/{evaluationId}/comments")
    public Map<String, Object> list(@PathVariable UUID evaluationId) {
        return Map.of("success", true, "data", comments.list(evaluationId));
    }

    @PostMapping("/evaluations/{evaluationId}/comments")
    public Map<String, Object> create(@PathVariable UUID evaluationId, @Valid @RequestBody CreateComment request,
                                      HttpServletRequest servletRequest) {
        return Map.of("success", true, "data", comments.create(evaluationId, request.operationId(),
            sanitize(request.content()), requestId(servletRequest)));
    }

    @PatchMapping("/comments/{commentId}")
    public Map<String, Object> revise(@PathVariable UUID commentId, @Valid @RequestBody ReviseComment request,
                                      HttpServletRequest servletRequest) {
        return Map.of("success", true, "data", comments.revise(commentId, request.operationId(),
            request.baseRevision(), sanitize(request.content()), requestId(servletRequest)));
    }

    @DeleteMapping("/comments/{commentId}")
    public Map<String, Object> delete(@PathVariable UUID commentId, @Valid @RequestBody DeleteComment request,
                                      HttpServletRequest servletRequest) {
        return Map.of("success", true, "data", comments.tombstone(commentId, request.operationId(),
            request.baseRevision(), requestId(servletRequest)));
    }

    @GetMapping("/evaluations/{evaluationId}/events")
    public Map<String, Object> events(@PathVariable UUID evaluationId,
                                      @RequestParam(defaultValue = "0") @Min(0) long afterSequence,
                                      @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        return Map.of("success", true, "data", comments.eventsAfter(evaluationId, afterSequence, limit));
    }

    private static String sanitize(String value) {
        if (value.indexOf('\0') >= 0 || value.matches("(?is).*<\\s*(script|iframe|object|embed)[^>]*>.*")) {
            throw new IllegalArgumentException("Contenido no permitido");
        }
        return value;
    }

    private static String requestId(HttpServletRequest request) {
        String value = request.getHeader("X-Request-ID");
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.substring(0, Math.min(96, value.length()));
    }

    public record CreateComment(@NotNull UUID operationId, @NotBlank @Size(max = 8000) String content) {}
    public record ReviseComment(@NotNull UUID operationId, @Min(1) int baseRevision,
                                @NotBlank @Size(max = 8000) String content) {}
    public record DeleteComment(@NotNull UUID operationId, @Min(1) int baseRevision) {}
}
