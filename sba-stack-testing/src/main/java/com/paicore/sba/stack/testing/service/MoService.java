package com.paicore.sba.stack.testing.service;

import com.paicore.sba.stack.openapi.model.SmsRecordData;
import com.paicore.sba.stack.testing.controller.dto.DecodedTpdu;
import com.paicore.sba.stack.testing.service.model.MoResult;
import com.paicore.sba.stack.utils.ErrorCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SMSF-side processing of an MO sendsms request, decoupled from HTTP.
 * The controller layer hands in an already-decoded payload — {@link
 * SmsRecordData} (the multipart JSON root) and {@link DecodedTpdu} (the
 * 3GPP TS23.040 user data) — and this service applies the SMSF rules:
 *
 * <ul>
 *   <li>The originating UE must be registered (looked up in
 *       {@link RegistrationService#isRegistered}).</li>
 *   <li>The TPDU MTI must be {@code SMS_SUBMIT} — anything else is
 *       a TS29.540 §6.3.5 violation.</li>
 *   <li>Accepted MOs are captured in-memory so the testing harness can
 *       assert on what the SMSF observed.</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class MoService {

    private final RegistrationService registry;
    private final List<MoCapture> captures = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();

    public MoResult processMo(String supi, SmsRecordData smsRecord, DecodedTpdu tpdu) {
        if (smsRecord == null || tpdu == null) {
            rejected.incrementAndGet();
            return MoResult.rejected(ErrorCodes.INVALID_MO_PAYLOAD,
                    "SmsRecordData and DecodedTpdu are required");
        }
        if (!registry.isRegistered(supi)) {
            rejected.incrementAndGet();
            return MoResult.rejected(ErrorCodes.UE_NOT_REGISTERED,
                    "No active session for SUPI " + supi);
        }
        if (!"SMS_SUBMIT".equals(tpdu.mti())) {
            rejected.incrementAndGet();
            return MoResult.rejected(ErrorCodes.INVALID_MO_PAYLOAD,
                    "MO must carry SMS_SUBMIT, got " + tpdu.mti());
        }
        captures.add(new MoCapture(supi, smsRecord, tpdu));
        accepted.incrementAndGet();
        log.info("[mo-svc] accepted supi={} smsRecordId={} mti={} dcs=0x{} text='{}'",
                supi, smsRecord.getSmsRecordId(), tpdu.mti(),
                String.format("%02x", tpdu.dataCodingScheme()), tpdu.decodedMessage());
        return MoResult.accepted(smsRecord.getSmsRecordId(), tpdu.mti(), tpdu.decodedMessage());
    }

    public List<MoCapture> captures() { return captures; }
    public int acceptedCount() { return accepted.get(); }
    public int rejectedCount() { return rejected.get(); }

    public void clear() {
        captures.clear();
        accepted.set(0);
        rejected.set(0);
    }

    /** Snapshot of one accepted MO so tests can assert. */
    public record MoCapture(String supi, SmsRecordData smsRecord, DecodedTpdu tpdu) { }
}
