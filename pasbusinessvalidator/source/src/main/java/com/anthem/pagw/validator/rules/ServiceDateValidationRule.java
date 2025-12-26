package com.anthem.pagw.validator.rules;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.validator.model.ValidationError;
import com.anthem.pagw.validator.model.ValidationWarning;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates service dates and billable periods.
 */
@Component
public class ServiceDateValidationRule implements ValidationRule {

    // Maximum days in the future for service dates
    private static final int MAX_FUTURE_DAYS = 365;
    
    // Maximum days in the past for prior auth (typically 1 year)
    private static final int MAX_PAST_DAYS = 365;

    @Override
    public boolean isApplicable(JsonNode data, PagwMessage message) {
        return data.has("serviceDate") || data.has("billablePeriodStart") || data.has("lineItems");
    }

    @Override
    public RuleResult validate(JsonNode data, PagwMessage message) {
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        
        LocalDate today = LocalDate.now();
        
        // Validate main service date
        String serviceDate = data.path("serviceDate").asText();
        if (!serviceDate.isEmpty()) {
            validateDate(serviceDate, "serviceDate", today, errors, warnings);
        }
        
        // Validate billable period
        String periodStart = data.path("billablePeriodStart").asText();
        String periodEnd = data.path("billablePeriodEnd").asText();
        
        if (!periodStart.isEmpty()) {
            LocalDate start = validateDate(periodStart, "billablePeriodStart", today, errors, warnings);
            
            if (!periodEnd.isEmpty() && start != null) {
                LocalDate end = parseDate(periodEnd);
                if (end != null && end.isBefore(start)) {
                    errors.add(new ValidationError(
                            "INVALID_PERIOD",
                            "Billable period end date cannot be before start date",
                            "billablePeriodEnd"
                    ));
                }
                
                // Check for excessively long periods
                if (end != null && start.plusDays(90).isBefore(end)) {
                    warnings.add(new ValidationWarning(
                            "LONG_SERVICE_PERIOD",
                            "Billable period exceeds 90 days - verify this is correct",
                            "billablePeriod"
                    ));
                }
            }
        }
        
        // Validate line item service dates
        JsonNode lineItems = data.path("lineItems");
        if (lineItems.isArray()) {
            int seq = 0;
            for (JsonNode item : lineItems) {
                seq++;
                String itemDate = item.path("serviceDate").asText();
                if (!itemDate.isEmpty()) {
                    validateDate(itemDate, "lineItems[" + seq + "].serviceDate", today, errors, warnings);
                }
            }
        }
        
        return new RuleResult(errors, warnings);
    }

    private LocalDate validateDate(String dateStr, String fieldPath, LocalDate today,
                                    List<ValidationError> errors, List<ValidationWarning> warnings) {
        LocalDate date = parseDate(dateStr);
        
        if (date == null) {
            errors.add(new ValidationError(
                    "INVALID_DATE_FORMAT",
                    String.format("Invalid date format: %s. Expected YYYY-MM-DD", dateStr),
                    fieldPath
            ));
            return null;
        }
        
        // Check if date is too far in the future
        if (date.isAfter(today.plusDays(MAX_FUTURE_DAYS))) {
            errors.add(new ValidationError(
                    "DATE_TOO_FAR_FUTURE",
                    String.format("Service date %s is more than %d days in the future", dateStr, MAX_FUTURE_DAYS),
                    fieldPath
            ));
        } else if (date.isAfter(today)) {
            // Future date within acceptable range - this is normal for prior auth
            // Just informational, not even a warning
        }
        
        // Check if date is too far in the past
        if (date.isBefore(today.minusDays(MAX_PAST_DAYS))) {
            warnings.add(new ValidationWarning(
                    "DATE_TOO_OLD",
                    String.format("Service date %s is more than %d days in the past - verify eligibility", dateStr, MAX_PAST_DAYS),
                    fieldPath
            ));
        }
        
        return date;
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        
        try {
            // Try ISO date format first (YYYY-MM-DD)
            if (dateStr.length() >= 10) {
                return LocalDate.parse(dateStr.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
            }
        } catch (DateTimeParseException e) {
            // Fall through to return null
        }
        
        return null;
    }

    @Override
    public String getName() {
        return "SERVICE_DATE_VALIDATION";
    }

    @Override
    public int getPriority() {
        return 70;
    }
}
