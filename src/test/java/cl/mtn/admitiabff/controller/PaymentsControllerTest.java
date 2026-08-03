package cl.mtn.admitiabff.controller;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cl.mtn.admitiabff.service.AuthService;
import cl.mtn.admitiabff.service.payments.PaymentService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentsControllerTest {

    @Test
    void adminUsesAdministrativeStatusReconciliation() {
        PaymentService paymentService = mock(PaymentService.class);
        AuthService authService = mock(AuthService.class);
        PaymentsController controller = new PaymentsController(paymentService, authService);
        AuthService.AuthContextHolder auth = new AuthService.AuthContextHolder(99L, "admin@mtn.cl", "ADMIN");
        Map<String, Object> expected = Map.of("success", true);
        when(authService.requireAuth()).thenReturn(auth);
        when(authService.isAdminContext(auth)).thenReturn(true);
        when(paymentService.statusForAdmin(20L)).thenReturn(expected);

        Map<String, Object> response = controller.status(20L);

        assertSame(expected, response);
        verify(paymentService).statusForAdmin(20L);
        verify(paymentService, never()).status(20L, 99L);
    }

    @Test
    void guardianKeepsOwnershipProtectedStatusReconciliation() {
        PaymentService paymentService = mock(PaymentService.class);
        AuthService authService = mock(AuthService.class);
        PaymentsController controller = new PaymentsController(paymentService, authService);
        AuthService.AuthContextHolder auth = new AuthService.AuthContextHolder(7L, "guardian@example.cl", "APODERADO");
        Map<String, Object> expected = Map.of("success", true);
        when(authService.requireAuth()).thenReturn(auth);
        when(authService.isAdminContext(auth)).thenReturn(false);
        when(paymentService.status(20L, 7L)).thenReturn(expected);

        Map<String, Object> response = controller.status(20L);

        assertSame(expected, response);
        verify(paymentService).status(20L, 7L);
        verify(paymentService, never()).statusForAdmin(20L);
    }
}
