package com.paicore.sba.stack.api.n21;

import com.paicore.sba.stack.openapi.model.Guami;
import com.paicore.sba.stack.openapi.model.PlmnId;
import com.paicore.sba.stack.openapi.model.SmsfRegistration;
import com.paicore.sba.stack.openapi.model.UeSmsContextData;
import com.paicore.sba.stack.utils.SdkConverter;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class N21RegistrationMapperTest {

    private static final String INSTANCE_ID = "8f0d7f6d-62c5-4fd7-ba4c-f8cb9df83a75";

    private static UeSmsContextData ue(String supi, String mcc, String mnc) {
        UeSmsContextData context = new UeSmsContextData();
        context.setSupi(supi);
        if (mcc != null) {
            Guami guami = new Guami();
            guami.setPlmnId(new PlmnId().mcc(mcc).mnc(mnc));
            context.setGuamis(List.of(guami));
        }
        return context;
    }

    @Test
    void buildsMandatoryFieldsWithPlmnFromGuami() {
        SmsfContext ctx = SmsfContext.builder().smsfInstanceId(INSTANCE_ID).build();

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(
                ctx, ue("imsi-001010000000001", "001", "01"));

        assertThat(reg.getSmsfInstanceId()).isEqualTo(UUID.fromString(INSTANCE_ID));
        assertThat(reg.getPlmnId().getMcc()).isEqualTo("001");
        assertThat(reg.getPlmnId().getMnc()).isEqualTo("01");
        assertThat(reg.getSmsfMAPAddress()).isNull();
        assertThat(reg.getSmsfDiameterAddress()).isNull();
    }

    @Test
    void omitsTheGeneratorDefaultsSoTheyNeverReachTheUdm() {
        SmsfContext ctx = SmsfContext.builder().smsfInstanceId(INSTANCE_ID).build();

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(ctx, ue("imsi-1", "001", "01"));

        // resetIds defaults to an empty list (schema requires minItems 1) and the indicators to
        // false; all three must be absent unless the caller set them.
        assertThat(reg.getResetIds()).isNull();
        assertThat(reg.getUdrRestartInd()).isNull();
        assertThat(reg.getSmsfSbiSupInd()).isNull();
        assertThat(SdkConverter.valueAsString(reg))
                .doesNotContain("resetIds")
                .doesNotContain("udrRestartInd")
                .doesNotContain("smsfSbiSupInd");
    }

    @Test
    void sendsSmsfSbiSupIndWhenTheContextDeclaresIt() {
        SmsfContext ctx = SmsfContext.builder().smsfInstanceId(INSTANCE_ID).smsfSbiSupInd(true).build();

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(ctx, ue("imsi-1", "001", "01"));

        assertThat(reg.getSmsfSbiSupInd()).isTrue();
        assertThat(SdkConverter.valueAsString(reg)).contains("\"smsfSbiSupInd\":true");
    }

    @Test
    void normalizesAGlobalTitleGivenInDiallingFormat() {
        SmsfContext ctx = SmsfContext.builder()
                .smsfInstanceId(INSTANCE_ID)
                .smsfMapAddress("+34 600-000.000")
                .build();

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(ctx, ue("imsi-1", "001", "01"));

        assertThat(reg.getSmsfMAPAddress()).isEqualTo("34600000000");
    }

    @Test
    void dropsAMapAddressThatIsNotAnE164Number() {
        SmsfContext ctx = SmsfContext.builder()
                .smsfInstanceId(INSTANCE_ID)
                .smsfMapAddress("not-a-number")
                .build();

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(ctx, ue("imsi-1", "001", "01"));

        // Dropped rather than sent malformed: a 400 would cost the whole registration.
        assertThat(reg.getSmsfMAPAddress()).isNull();
        assertThat(reg.getSmsfInstanceId()).isNotNull();
    }

    @Test
    void dropsAMapAddressLongerThanFifteenDigits() {
        SmsfContext ctx = SmsfContext.builder()
                .smsfInstanceId(INSTANCE_ID)
                .smsfMapAddress("1234567890123456")
                .build();

        assertThat(N21RegistrationMapper.buildRegistration(ctx, ue("imsi-1", "001", "01"))
                .getSmsfMAPAddress()).isNull();
    }

    @Test
    void dropsADiameterAddressWhoseIdentityIsNotAnFqdn() {
        SmsfContext ctx = SmsfContext.builder()
                .smsfInstanceId(INSTANCE_ID)
                .smsfDiameterAddress("smsfnode")
                .build();

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(ctx, ue("imsi-1", "001", "01"));

        assertThat(reg.getSmsfDiameterAddress()).isNull();
    }

    @Test
    void populatesOptionalInterworkingAddresses() {
        SmsfContext ctx = SmsfContext.builder()
                .smsfInstanceId(INSTANCE_ID)
                .smsfMapAddress("1234567890")
                .smsfDiameterAddress("smsf.node@example.com")
                .build();

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(
                ctx, ue("imsi-310260000000001", "310", "260"));

        assertThat(reg.getSmsfMAPAddress()).isEqualTo("1234567890");
        assertThat(reg.getSmsfDiameterAddress().getName()).isEqualTo("smsf.node");
        assertThat(reg.getSmsfDiameterAddress().getRealm()).isEqualTo("example.com");
    }

    @Test
    void diameterAddressWithoutRealmUsesValueForBoth() {
        SmsfContext ctx = SmsfContext.builder()
                .smsfInstanceId(INSTANCE_ID)
                .smsfDiameterAddress("smsf.example.com")
                .build();

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(ctx, ue("imsi-1", "001", "01"));

        assertThat(reg.getSmsfDiameterAddress().getName()).isEqualTo("smsf.example.com");
        assertThat(reg.getSmsfDiameterAddress().getRealm()).isEqualTo("smsf.example.com");
    }

    @Test
    void rejectsInvalidInstanceId() {
        SmsfContext ctx = SmsfContext.builder().smsfInstanceId("not-a-uuid").build();
        UeSmsContextData ueContext = ue("imsi-1", "001", "01");
        assertThatThrownBy(() -> N21RegistrationMapper.buildRegistration(ctx, ueContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("smsfInstanceId");
    }

    @Test
    void rejectsMissingGuamiPlmn() {
        SmsfContext ctx = SmsfContext.builder().smsfInstanceId(INSTANCE_ID).build();
        UeSmsContextData ueContext = ue("imsi-1", null, null);
        assertThatThrownBy(() -> N21RegistrationMapper.buildRegistration(ctx, ueContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plmnId");
    }

    @Test
    void fallsBackToTheHomePlmnWhenTheAmfSuppliesNoGuami() {
        SmsfContext ctx = SmsfContext.builder()
                .smsfInstanceId(INSTANCE_ID)
                .homePlmnMcc("214")
                .homePlmnMnc("07")
                .build();

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(ctx, ue("imsi-1", null, null));

        assertThat(reg.getPlmnId().getMcc()).isEqualTo("214");
        assertThat(reg.getPlmnId().getMnc()).isEqualTo("07");
    }

    @Test
    void theServingPlmnFromTheAmfWinsOverTheHomePlmn() {
        SmsfContext ctx = SmsfContext.builder()
                .smsfInstanceId(INSTANCE_ID)
                .homePlmnMcc("214")
                .homePlmnMnc("07")
                .build();

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(ctx, ue("imsi-1", "001", "01"));

        assertThat(reg.getPlmnId().getMcc()).isEqualTo("001");
        assertThat(reg.getPlmnId().getMnc()).isEqualTo("01");
    }

    @Test
    void anIncompleteGuamiPlmnIsStillRejected() {
        SmsfContext ctx = SmsfContext.builder()
                .smsfInstanceId(INSTANCE_ID)
                .homePlmnMcc("214")
                .homePlmnMnc("07")
                .build();
        UeSmsContextData context = new UeSmsContextData();
        context.setSupi("imsi-1");
        Guami guami = new Guami();
        guami.setPlmnId(new PlmnId().mcc("001"));
        context.setGuamis(List.of(guami));

        assertThatThrownBy(() -> N21RegistrationMapper.buildRegistration(ctx, context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mcc/mnc");
    }

    @Test
    void populatesTheOptionalSmsfIdentityFields() {
        SmsfContext ctx = SmsfContext.builder()
                .smsfInstanceId(INSTANCE_ID)
                .smsfSetId("set-smsf-1")
                .supportedFeatures("3fff")
                .smsfSbiSupInd(true)
                .build();

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(ctx, ue("imsi-1", "001", "01"));

        assertThat(reg.getSmsfSetId()).isEqualTo("set-smsf-1");
        assertThat(reg.getSupportedFeatures()).isEqualTo("3fff");
        assertThat(reg.getSmsfSbiSupInd()).isTrue();
    }

    @Test
    void stampsTheSuppliedRegistrationTime() {
        SmsfContext ctx = SmsfContext.builder().smsfInstanceId(INSTANCE_ID).build();
        OffsetDateTime when = OffsetDateTime.parse("2026-07-29T10:15:30Z");

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(ctx, ue("imsi-1", "001", "01"), when);

        assertThat(reg.getRegistrationTime()).isEqualTo(when);
    }

    @Test
    void omitsTheRegistrationTimeWhenExplicitlyNull() {
        SmsfContext ctx = SmsfContext.builder().smsfInstanceId(INSTANCE_ID).build();

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(ctx, ue("imsi-1", "001", "01"), null);

        assertThat(reg.getRegistrationTime()).isNull();
    }

    @Test
    void stampsTheCurrentTimeByDefault() {
        SmsfContext ctx = SmsfContext.builder().smsfInstanceId(INSTANCE_ID).build();

        SmsfRegistration reg = N21RegistrationMapper.buildRegistration(ctx, ue("imsi-1", "001", "01"));

        assertThat(reg.getRegistrationTime()).isNotNull();
    }
}
