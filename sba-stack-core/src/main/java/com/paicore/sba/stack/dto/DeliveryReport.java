package com.paicore.sba.stack.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paicore.sba.stack.dto.enums.DeliveryStatus;
import lombok.Generated;
import com.paicore.sba.stack.utils.SdkConverter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Generated
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeliveryReport {

    private String correlationId;
    private String smsRecordId;
    private String gpsi;
    private DeliveryStatus status;
    private Instant deliveryTimestamp;
    private Integer reasonCode;
    private String reasonText;

    @Override
    public String toString() {
        return SdkConverter.valueAsString(this);
    }
}
