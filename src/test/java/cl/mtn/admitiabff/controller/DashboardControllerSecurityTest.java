package cl.mtn.admitiabff.controller;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.service.DashboardService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = DashboardControllerSecurityTest.TestConfig.class)
class DashboardControllerSecurityTest {

    @Autowired private DashboardController controller;
    @Autowired private DashboardService dashboardService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void applicantCardRejectsAuthenticatedNonAdminUsers() {
        authenticateAs("ROLE_COORDINATOR");

        assertThrows(AccessDeniedException.class, () -> controller.applicantCard(7L));
        verifyNoInteractions(dashboardService);
    }

    @Test
    void applicantCardAllowsAdminUsers() {
        authenticateAs("ROLE_ADMIN");
        Map<String, Object> expected = Map.of("success", true);
        when(dashboardService.applicantCard(7L)).thenReturn(expected);

        assertSame(expected, controller.applicantCard(7L));
    }

    private void authenticateAs(String authority) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "test-user",
                "not-used",
                List.of(new SimpleGrantedAuthority(authority))
            )
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        DashboardService dashboardService() {
            return mock(DashboardService.class);
        }

        @Bean
        DashboardController dashboardController(DashboardService dashboardService) {
            return new DashboardController(dashboardService);
        }
    }
}
