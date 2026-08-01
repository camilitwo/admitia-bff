package cl.mtn.admitiabff.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.domain.common.Role;
import cl.mtn.admitiabff.domain.user.UserEntity;
import cl.mtn.admitiabff.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class TemporaryPasswordEnforcementFilterTest {
    @Mock UserRepository userRepository;

    @AfterEach
    void clearContext() { AuthContext.clear(); }

    @Test
    void blocksFunctionalEndpointsWhileAllowingTheMandatoryChange() throws Exception {
        UserEntity user = temporaryUser(LocalDateTime.now().plusHours(1));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        AuthContext.set(new AuthUser(10L, user.getEmail(), user.getRole().name()));
        TemporaryPasswordEnforcementFilter filter = new TemporaryPasswordEnforcementFilter(userRepository);

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/applications"), blocked, new MockFilterChain());
        assertThat(blocked.getStatus()).isEqualTo(403);
        assertThat(blocked.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");

        MockHttpServletResponse allowed = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("PUT", "/api/auth/change-temporary-password"), allowed, chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void reportsExpiredTemporaryCredentialWithStableCode() throws Exception {
        UserEntity user = temporaryUser(LocalDateTime.now().minusMinutes(1));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        AuthContext.set(new AuthUser(10L, user.getEmail(), user.getRole().name()));
        TemporaryPasswordEnforcementFilter filter = new TemporaryPasswordEnforcementFilter(userRepository);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/users/me"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("TEMPORARY_PASSWORD_EXPIRED");
    }

    private UserEntity temporaryUser(LocalDateTime expiresAt) {
        UserEntity user = new UserEntity();
        user.setId(10L);
        user.setEmail("profesor@cmtn.cl");
        user.setRole(Role.TEACHER);
        user.setMustChangePassword(true);
        user.setTemporaryPasswordExpiresAt(expiresAt);
        return user;
    }
}
