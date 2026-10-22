package com.paicore.sba.stack.testing.controller;

import com.paicore.sba.stack.codec.SmsTpduCodec;
import com.paicore.sba.stack.codec.Ss7SmsTpduCodec;
import com.paicore.sba.stack.exceptions.SdkException;
import com.paicore.sba.stack.mo.MoMultipartPayload;
import com.paicore.sba.stack.mo.MultipartRelatedDecoder;
import com.paicore.sba.stack.testing.controller.dto.DecodedTpdu;
import com.paicore.sba.stack.testing.controller.dto.HttpResult;
import com.paicore.sba.stack.testing.service.MoService;
import com.paicore.sba.stack.testing.service.model.MoResult;
import com.paicore.sba.stack.utils.ErrorCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsTpdu;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Glue between HTTP wire framing and the {@link MoService}. Responsibilities
 * at the 3GPP TS29.540 {@code POST /ue-contexts/{supi}/sendsms} endpoint:
 *
 * <ol>
 *   <li>Take the {@code Content-Type} and raw body bytes off the request.</li>
 *   <li>Use the stack's {@link MultipartRelatedDecoder} to split the body
 *       into the JSON {@code SmsRecordData} part and the binary
 *       {@code application/vnd.3gpp.sms} TPDU part.</li>
 *   <li>Use the stack's {@link SmsTpduCodec} (jss7) to decode the binary
 *       part — and wrap it in {@link DecodedTpdu} so the service receives
 *       typed inputs.</li>
 *   <li>Delegate to {@link MoService#processMo(String, com.paicore.sba.stack.openapi.model.SmsRecordData, DecodedTpdu)}.
 *       The service is fully HTTP-unaware.</li>
 *   <li>Map the service's {@link MoResult} back into an {@link HttpResult}.</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class MoController {

    private final MoService service;
    private final SmsTpduCodec codec = new Ss7SmsTpduCodec();

    public HttpResult sendsms(String supi, byte[] body, Map<String, String> headers) {
        String contentType = headers.get("content-type");

        MoMultipartPayload multipart;
        try {
            multipart = MultipartRelatedDecoder.decode(body, contentType);
        } catch (SdkException e) {
            log.info("[mo-ctrl] multipart decode failed: code={} detail={}", e.getCode(), e.getDetails());
            return HttpResult.badRequest(e.getCode(), e.getDetails());
        }
        if (multipart.getBinaryData() == null) {
            return HttpResult.badRequest(ErrorCodes.INVALID_MO_PAYLOAD,
                    "multipart/related body is missing the application/vnd.3gpp.sms part");
        }

        DecodedTpdu tpdu;
        try {
            SmsTpdu raw = codec.decode(multipart.getBinaryData());
            tpdu = DecodedTpdu.fromJss7(raw, multipart.getBinaryData());
        } catch (SdkException e) {
            log.info("[mo-ctrl] TPDU decode failed: code={} detail={}", e.getCode(), e.getDetails());
            return HttpResult.badRequest(e.getCode(), e.getDetails());
        }

        MoResult result = service.processMo(supi, multipart.getJsonData(), tpdu);
        if (!result.accepted()) {
            int status = ErrorCodes.UE_NOT_REGISTERED.equals(result.errorCode()) ? 404 : 400;
            return HttpResult.problem(status, result.errorCode(), result.errorDetail());
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("smsRecordId", result.smsRecordId());
        resp.put("accepted", true);
        resp.put("tpduType", result.tpduType());
        resp.put("decodedMessage", result.decodedMessage());
        return HttpResult.accepted(resp);
    }
}
