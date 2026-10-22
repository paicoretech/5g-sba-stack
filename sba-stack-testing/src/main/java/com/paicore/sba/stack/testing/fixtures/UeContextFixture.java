package com.paicore.sba.stack.testing.fixtures;

import com.paicore.sba.stack.openapi.model.AccessType;
import com.paicore.sba.stack.openapi.model.Guami;
import com.paicore.sba.stack.openapi.model.NrLocation;
import com.paicore.sba.stack.openapi.model.Ncgi;
import com.paicore.sba.stack.openapi.model.PlmnId;
import com.paicore.sba.stack.openapi.model.RatType;
import com.paicore.sba.stack.openapi.model.Tai;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.openapi.model.UserLocation;

import java.util.List;
import java.util.UUID;

/**
 * Builds realistic UeSmsContextData payloads as required by the
 * 3GPP TS29.540 {@code POST /ue-contexts/{supi}} registration endpoint.
 */
public final class UeContextFixture {

    public static final UUID DEFAULT_AMF_ID = UUID.fromString("54804518-e30d-11e8-9f32-f2801f1b9fd1");
    public static final String DEFAULT_SUPI = "imsi-460000000000001";

    private UeContextFixture() {
        throw new IllegalStateException("Utility class");
    }

    public static UeSmsContextData defaultContext() {
        return contextFor(DEFAULT_SUPI);
    }

    public static UeSmsContextData contextFor(String supi) {
        PlmnId plmn = new PlmnId().mcc("460").mnc("00");

        Guami guami = new Guami().plmnId(plmn).amfId("A00001");

        Tai tai = new Tai().plmnId(plmn).tac("A01001");
        Ncgi ncgi = new Ncgi().plmnId(plmn).nrCellId("225BD6007");
        NrLocation nr = new NrLocation().tai(tai).ncgi(ncgi);
        UserLocation location = new UserLocation().nrLocation(nr);

        UeSmsContextData ctx = new UeSmsContextData();
        ctx.setSupi(supi);
        ctx.setGpsi("msisdn-8613915900000");
        ctx.setPei("imei-490154203237518");
        ctx.setAmfId(DEFAULT_AMF_ID);
        ctx.setAccessType(AccessType._3_GPP_ACCESS);
        ctx.setGuamis(List.of(guami));
        ctx.setUeLocation(location);
        ctx.setUeTimeZone("+08:00");
        ctx.setRatType(RatType.NR);
        return ctx;
    }
}
