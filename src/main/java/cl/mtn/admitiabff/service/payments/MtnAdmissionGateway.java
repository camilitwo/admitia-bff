package cl.mtn.admitiabff.service.payments;

import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.AdmissionRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.AdmissionResponse;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeRequest;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeResponse;
import cl.mtn.admitiabff.service.payments.MtnAdmissionDtos.ChargeStatusResponse;

public interface MtnAdmissionGateway {
    AdmissionResponse synchronizeAdmission(AdmissionRequest body);
    ChargeResponse createCharge(ChargeRequest body);
    ChargeStatusResponse chargeStatus(Long chargeId);
}
