package com.anthem.pagw.validator.rules;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.validator.model.ValidationError;
import com.anthem.pagw.validator.model.ValidationWarning;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates attachment/supporting documentation.
 */
@Component
public class AttachmentValidationRule implements ValidationRule {

    // Maximum attachment size (10 MB)
    private static final long MAX_ATTACHMENT_SIZE = 10 * 1024 * 1024;
    
    // Maximum total attachment size (50 MB)
    private static final long MAX_TOTAL_SIZE = 50 * 1024 * 1024;
    
    // Maximum number of attachments
    private static final int MAX_ATTACHMENTS = 20;
    
    // Allowed content types
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/tiff",
            "text/plain",
            "text/xml",
            "application/xml",
            "application/json",
            "application/fhir+json"
    );
    
    // Suspicious content types that might indicate errors
    private static final Set<String> SUSPICIOUS_TYPES = Set.of(
            "application/octet-stream",
            "application/x-executable",
            "application/x-msdownload"
    );

    @Override
    public boolean isApplicable(JsonNode data, PagwMessage message) {
        return data.has("attachments") && data.path("attachments").isArray() && 
               data.path("attachments").size() > 0;
    }

    @Override
    public RuleResult validate(JsonNode data, PagwMessage message) {
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        
        JsonNode attachments = data.path("attachments");
        
        // Check attachment count
        if (attachments.size() > MAX_ATTACHMENTS) {
            errors.add(new ValidationError(
                    "TOO_MANY_ATTACHMENTS",
                    String.format("Maximum %d attachments allowed, found %d", MAX_ATTACHMENTS, attachments.size()),
                    "attachments"
            ));
        }
        
        long totalSize = 0;
        int index = 0;
        
        for (JsonNode attachment : attachments) {
            index++;
            String type = attachment.path("type").asText(); // DocumentReference or Binary
            String contentType = attachment.path("contentType").asText();
            String title = attachment.path("title").asText();
            
            // Validate content type
            if (contentType.isEmpty()) {
                errors.add(new ValidationError(
                        "MISSING_CONTENT_TYPE",
                        String.format("Attachment %d is missing content type", index),
                        "attachments[" + index + "].contentType"
                ));
            } else if (SUSPICIOUS_TYPES.contains(contentType.toLowerCase())) {
                errors.add(new ValidationError(
                        "SUSPICIOUS_CONTENT_TYPE",
                        String.format("Attachment %d has suspicious content type: %s", index, contentType),
                        "attachments[" + index + "].contentType"
                ));
            } else if (!ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
                warnings.add(new ValidationWarning(
                        "UNRECOGNIZED_CONTENT_TYPE",
                        String.format("Attachment %d has unrecognized content type: %s", index, contentType),
                        "attachments[" + index + "].contentType"
                ));
            }
            
            // For Binary type, check data size
            if ("Binary".equals(type)) {
                String data64 = attachment.path("data").asText();
                if (!data64.isEmpty()) {
                    // Base64 size is approximately 4/3 of original
                    long estimatedSize = (long) (data64.length() * 0.75);
                    
                    if (estimatedSize > MAX_ATTACHMENT_SIZE) {
                        errors.add(new ValidationError(
                                "ATTACHMENT_TOO_LARGE",
                                String.format("Attachment %d exceeds maximum size of %d MB", index, MAX_ATTACHMENT_SIZE / (1024 * 1024)),
                                "attachments[" + index + "]"
                        ));
                    }
                    
                    totalSize += estimatedSize;
                }
            }
            
            // For DocumentReference, validate URL
            if ("DocumentReference".equals(type)) {
                String url = attachment.path("url").asText();
                if (url.isEmpty()) {
                    errors.add(new ValidationError(
                            "MISSING_ATTACHMENT_URL",
                            String.format("DocumentReference attachment %d is missing URL", index),
                            "attachments[" + index + "].url"
                    ));
                } else if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("Binary/")) {
                    warnings.add(new ValidationWarning(
                            "INVALID_ATTACHMENT_URL",
                            String.format("Attachment %d has unusual URL format: %s", index, url),
                            "attachments[" + index + "].url"
                    ));
                }
            }
            
            // Warn if no title/description
            if (title.isEmpty()) {
                warnings.add(new ValidationWarning(
                        "MISSING_ATTACHMENT_TITLE",
                        String.format("Attachment %d is missing title/description", index),
                        "attachments[" + index + "].title"
                ));
            }
        }
        
        // Check total size
        if (totalSize > MAX_TOTAL_SIZE) {
            errors.add(new ValidationError(
                    "TOTAL_SIZE_EXCEEDED",
                    String.format("Total attachment size exceeds maximum of %d MB", MAX_TOTAL_SIZE / (1024 * 1024)),
                    "attachments"
            ));
        }
        
        return new RuleResult(errors, warnings);
    }

    @Override
    public String getName() {
        return "ATTACHMENT_VALIDATION";
    }

    @Override
    public int getPriority() {
        return 60; // Lower priority - run after core validations
    }
}
