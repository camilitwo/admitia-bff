package cl.mtn.admitiabff.prekinder.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrekinderParentDeliveryTest {
    @Test
    void createsIndependentDeliveriesForValidParents() {
        assertThat(PrekinderFlowService.parentDeliveries("padre@example.cl", "madre@example.cl"))
            .extracting(PrekinderFlowService.ParentDelivery::recipientKind,
                PrekinderFlowService.ParentDelivery::status,
                PrekinderFlowService.ParentDelivery::errorCode)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("FATHER", "PENDING", null),
                org.assertj.core.groups.Tuple.tuple("MOTHER", "PENDING", null));
    }

    @Test
    void skipsMissingInvalidAndDuplicateContactsWithoutRetryingThem() {
        assertThat(PrekinderFlowService.parentDeliveries(" correo-invalido ", null))
            .extracting(PrekinderFlowService.ParentDelivery::status,
                PrekinderFlowService.ParentDelivery::errorCode)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("SKIPPED", "RECIPIENT_INVALID"),
                org.assertj.core.groups.Tuple.tuple("SKIPPED", "RECIPIENT_MISSING"));

        assertThat(PrekinderFlowService.parentDeliveries("PADRE@example.cl ", " padre@example.cl"))
            .extracting(PrekinderFlowService.ParentDelivery::status,
                PrekinderFlowService.ParentDelivery::errorCode)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("PENDING", null),
                org.assertj.core.groups.Tuple.tuple("SKIPPED", "RECIPIENT_DUPLICATE"));
    }
}
