package cl.mtn.admitiabff.prekinder.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import cl.mtn.admitiabff.prekinder.config.PrekinderProperties;
import jakarta.servlet.FilterChain;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PrekinderMtlsFilterTest {
    private final PrekinderMtlsFilter filter = new PrekinderMtlsFilter(new PrekinderProperties(
        true, null, null, null, new PrekinderProperties.Mtls(true, 8443, "", "", "", "", "", "")
    ));

    @Test
    void refusesLegacyConnectorEvenWhenRouteIsAuthenticated() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/prekinder/evaluations");
        request.setLocalPort(8080);
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        verifyNoInteractions(chain);
    }

    @Test
    void acceptsOnlySecureDedicatedConnectorWithClientCertificate() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/prekinder/evaluations");
        request.setSecure(true);
        request.setLocalPort(8443);
        request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[]{mock(X509Certificate.class)});
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
