package cl.mtn.admitiabff.prekinder.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.config.JwtService;
import cl.mtn.admitiabff.prekinder.repository.PrekinderActorRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PrekinderAuthenticationFilterTest {
    private final JwtService jwtService = mock(JwtService.class);
    private final PrekinderActorRepository actors = mock(PrekinderActorRepository.class);
    private final PrekinderAuthenticationFilter filter = new PrekinderAuthenticationFilter(jwtService, actors, "");

    @Test
    void rejectsRequestWithoutBearerToken() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/prekinder/application-options");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        verifyNoInteractions(chain, jwtService, actors);
    }

    @Test
    void allowsPublicProfessionalRegistrationWithoutBearerToken() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/prekinder/professional-registration");
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        org.mockito.Mockito.verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtService, actors);
    }

    @Test
    void doesNotMaskActorStorageFailureAsUnauthorized() {
        var request = authorizedRequest();
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        Claims claims = mock(Claims.class);
        when(jwtService.isValid("valid-token")).thenReturn(true);
        when(jwtService.extractAllClaims("valid-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("42");
        when(claims.get("email", String.class)).thenReturn("apoderado@example.cl");
        when(claims.get("role", String.class)).thenReturn("APODERADO");
        when(actors.upsertSubject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, chain));

        verifyNoInteractions(chain);
    }

    private static MockHttpServletRequest authorizedRequest() {
        var request = new MockHttpServletRequest("GET", "/api/prekinder/application-options");
        request.addHeader("Authorization", "Bearer valid-token");
        return request;
    }
}
