package com.paicore.sba.stack.testing.fixtures;

import com.paicore.sba.stack.openapi.model.AccessType;
import com.paicore.sba.stack.openapi.model.Ncgi;
import com.paicore.sba.stack.openapi.model.NrLocation;
import com.paicore.sba.stack.openapi.model.PlmnId;
import com.paicore.sba.stack.openapi.model.RefToBinaryData;
import com.paicore.sba.stack.openapi.model.SmsRecordData;
import com.paicore.sba.stack.openapi.model.Tai;
import com.paicore.sba.stack.openapi.model.UserLocation;

import java.util.UUID;

/**
 * Builds the JSON root part (SmsRecordData) of a 3GPP TS29.540
 * {@code POST /ue-contexts/{supi}/sendsms} multipart/related body.
 */
public final class SmsRecordFixture {

    public static final String DEFAULT_CONTENT_ID = "sms";

    private SmsRecordFixture() {
        throw new IllegalStateException("Utility class");
    }

    public static SmsRecordData defaultRecord() {
        return recordFor(UUID.randomUUID().toString());
    }

    public static SmsRecordData recordFor(String smsRecordId) {
        PlmnId plmn = new PlmnId().mcc("46").mnc("000");
        Tai tai = new Tai().plmnId(plmn).tac("A01001");
        Ncgi ncgi = new Ncgi().plmnId(plmn).nrCellId("225BD6007");
        NrLocation nr = new NrLocation().tai(tai).ncgi(ncgi);
        UserLocation location = new UserLocation().nrLocation(nr);

        SmsRecordData smsRecord = new SmsRecordData();
        smsRecord.setSmsRecordId(smsRecordId);
        smsRecord.setSmsPayload(new RefToBinaryData().contentId(DEFAULT_CONTENT_ID));
        smsRecord.setAccessType(AccessType._3_GPP_ACCESS);
        smsRecord.setGpsi("msisdn-8613915900000");
        smsRecord.setPei("imei-490154203237518");
        smsRecord.setUeLocation(location);
        smsRecord.setUeTimeZone("+08:00");
        return smsRecord;
    }
}
