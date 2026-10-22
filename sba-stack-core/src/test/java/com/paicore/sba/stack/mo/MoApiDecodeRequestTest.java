package com.paicore.sba.stack.mo;

import com.paicore.sba.stack.api.MoApi;
import com.paicore.sba.stack.dto.MoMessageRequest;
import com.paicore.sba.stack.dto.MoResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MoApiDecodeRequestTest {

    private final MoApi api = new MoApi() {
        @Override public MoResponse receiveMoMessage(MoMessageRequest request) { return null; }
    };

    @Test
    void defaultDecoderProducesStructuredPayload() {
        String boundary = "----Boundary";
        byte[] binary = {0x10, 0x20};
        String header = "--" + boundary + "\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + "{\"smsRecordId\":\"r-1\",\"smsPayload\":{\"contentId\":\"sms\"}}\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: application/vnd.3gpp.sms\r\n"
                + "Content-Id: sms\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";

        byte[] body = concat(header.getBytes(StandardCharsets.UTF_8), binary,
                footer.getBytes(StandardCharsets.UTF_8));

        MoMultipartPayload payload = api.decodeRequest(body,
                "multipart/related; boundary=" + boundary + "; type=application/json");
        assertNotNull(payload);
        assertEquals("sms", payload.getContentId());
        assertEquals("r-1", payload.getJsonData().getSmsRecordId());
        assertEquals(2, payload.getBinaryData().length);
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) { System.arraycopy(a, 0, out, pos, a.length); pos += a.length; }
        return out;
    }
}
