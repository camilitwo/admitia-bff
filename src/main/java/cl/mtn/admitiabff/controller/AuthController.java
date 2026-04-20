package cl.mtn.admitiabff.controller;

import cl.mtn.admitiabff.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/csrf-token")
    public Map<String, Object> csrfToken() { return authService.csrfToken(); }

    @GetMapping("/public-key")
    public Map<String, Object> publicKey() { return authService.publicKey(); }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        return authService.login(payload, request.getHeader("User-Agent"), request.getRemoteAddr());
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> payload) { return authService.register(payload); }

    @PostMapping("/check-email")
    public Map<String, Object> checkEmail(@RequestBody Map<String, Object> payload) { return authService.checkEmail(payload); }

    @GetMapping("/check")
    public Map<String, Object> check() { return authService.check(); }

    @PutMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody Map<String, Object> payload) { return authService.changePassword(payload); }
}
