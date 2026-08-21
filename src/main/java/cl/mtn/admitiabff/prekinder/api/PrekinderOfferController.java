package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderOfferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prekinder")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderOfferController {
    private final PrekinderOfferService offers;
    public PrekinderOfferController(PrekinderOfferService offers) { this.offers = offers; }
    @GetMapping("/me/offers") public Map<String, Object> mine() { return Map.of("success", true, "data", offers.mine()); }
    @PostMapping("/offers/{offerId}/response") public Map<String, Object> respond(@PathVariable UUID offerId,
        @Valid @RequestBody Response request) {
        return Map.of("success", true, "data", offers.respond(offerId, request.response(), request.expectedVersion()));
    }
    public record Response(@NotBlank @Pattern(regexp = "ACCEPTED|DECLINED") String response, @Min(0) long expectedVersion) {}
}
