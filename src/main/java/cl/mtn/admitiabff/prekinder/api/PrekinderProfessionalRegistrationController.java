package cl.mtn.admitiabff.prekinder.api;

import cl.mtn.admitiabff.prekinder.service.PrekinderProfessionalRegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prekinder/professional-registration")
@ConditionalOnProperty(prefix = "app.prekinder", name = "enabled", havingValue = "true")
public class PrekinderProfessionalRegistrationController {
    private final PrekinderProfessionalRegistrationService registration;

    public PrekinderProfessionalRegistrationController(PrekinderProfessionalRegistrationService registration) {
        this.registration = registration;
    }

    @PostMapping
    public Map<String, Object> register(@Valid @RequestBody RegistrationCommand command) {
        return Map.of("success", true, "data", registration.register(command.email(), command.password()));
    }

    public record RegistrationCommand(
        @Email @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(min = 6, max = 128) String password) {}
}
