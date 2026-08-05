package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.realtime.RealtimeTicketService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/prekinder/realtime")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderRealtimeController {
    private final RealtimeTicketService tickets;
    public PrekinderRealtimeController(RealtimeTicketService tickets) { this.tickets = tickets; }

    @PostMapping("/tickets")
    public Map<String, Object> ticket(HttpServletRequest request) {
        try { return Map.of("success", true, "data", tickets.issue(request.getHeader("Origin"))); }
        catch (org.springframework.data.redis.RedisConnectionFailureException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Colaboración en vivo no disponible");
        }
    }
}
