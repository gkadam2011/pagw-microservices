package com.anthem.pagw.validator.rules;

import com.anthem.pagw.core.model.PagwMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceDateValidationRuleTest {

    private ServiceDateValidationRule rule;
    private ObjectMapper mapper;
    private PagwMessage message;

    @BeforeEach
    void setUp() {
        rule = new ServiceDateValidationRule();
        mapper = new ObjectMapper();
        message = PagwMessage.builder()
                .pagwId("PAGW-TEST-001")
                .tenant("test-tenant")
                .build();
    }

    @Test
    void testIsApplicable_withServiceDate() {
        ObjectNode data = mapper.createObjectNode();
        data.put("serviceDate", "2025-12-26");
        assertThat(rule.isApplicable(data, message)).isTrue();
    }

    @Test
    void testIsApplicable_withBillablePeriod() {
        ObjectNode data = mapper.createObjectNode();
        data.put("billablePeriodStart", "2025-12-26");
        assertThat(rule.isApplicable(data, message)).isTrue();
    }

    @Test
    void testIsApplicable_withLineItems() {
        ObjectNode data = mapper.createObjectNode();
        data.putArray("lineItems");
        assertThat(rule.isApplicable(data, message)).isTrue();
    }

    @Test
    void testIsApplicable_noDateFields() {
        ObjectNode data = mapper.createObjectNode();
        data.put("claimId", "test");
        assertThat(rule.isApplicable(data, message)).isFalse();
    }

    @Test
    void testValidate_validCurrentDate() {
        ObjectNode data = mapper.createObjectNode();
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        data.put("serviceDate", today);

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void testValidate_validFutureDate() {
        ObjectNode data = mapper.createObjectNode();
        String futureDate = LocalDate.now().plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE);
        data.put("serviceDate", futureDate);

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void testValidate_dateTooFarInFuture() {
        ObjectNode data = mapper.createObjectNode();
        String farFuture = LocalDate.now().plusDays(400).format(DateTimeFormatter.ISO_LOCAL_DATE);
        data.put("serviceDate", farFuture);

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getCode()).isEqualTo("DATE_TOO_FAR_FUTURE");
    }

    @Test
    void testValidate_dateTooOld_warning() {
        ObjectNode data = mapper.createObjectNode();
        String oldDate = LocalDate.now().minusDays(400).format(DateTimeFormatter.ISO_LOCAL_DATE);
        data.put("serviceDate", oldDate);

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("DATE_TOO_OLD"));
    }

    @Test
    void testValidate_invalidDateFormat() {
        ObjectNode data = mapper.createObjectNode();
        data.put("serviceDate", "12/26/2025"); // Wrong format

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getCode()).isEqualTo("INVALID_DATE_FORMAT");
    }

    @Test
    void testValidate_validBillablePeriod() {
        ObjectNode data = mapper.createObjectNode();
        String start = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String end = LocalDate.now().plusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE);
        data.put("billablePeriodStart", start);
        data.put("billablePeriodEnd", end);

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void testValidate_billablePeriodEndBeforeStart() {
        ObjectNode data = mapper.createObjectNode();
        String start = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String end = LocalDate.now().minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE);
        data.put("billablePeriodStart", start);
        data.put("billablePeriodEnd", end);

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getCode()).isEqualTo("INVALID_PERIOD");
    }

    @Test
    void testValidate_longServicePeriod_warning() {
        ObjectNode data = mapper.createObjectNode();
        String start = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String end = LocalDate.now().plusDays(120).format(DateTimeFormatter.ISO_LOCAL_DATE);
        data.put("billablePeriodStart", start);
        data.put("billablePeriodEnd", end);

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("LONG_SERVICE_PERIOD"));
    }

    @Test
    void testValidate_lineItemServiceDate() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode lineItems = data.putArray("lineItems");
        
        ObjectNode item = lineItems.addObject();
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        item.put("serviceDate", today);

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void testValidate_lineItemInvalidDate() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode lineItems = data.putArray("lineItems");
        
        ObjectNode item = lineItems.addObject();
        String farFuture = LocalDate.now().plusDays(500).format(DateTimeFormatter.ISO_LOCAL_DATE);
        item.put("serviceDate", farFuture);

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getCode()).isEqualTo("DATE_TOO_FAR_FUTURE");
    }

    @Test
    void testGetName() {
        assertThat(rule.getName()).isEqualTo("SERVICE_DATE_VALIDATION");
    }

    @Test
    void testGetPriority() {
        assertThat(rule.getPriority()).isEqualTo(70);
    }
}
