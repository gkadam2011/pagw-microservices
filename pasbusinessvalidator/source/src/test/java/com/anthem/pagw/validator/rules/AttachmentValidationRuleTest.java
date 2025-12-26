package com.anthem.pagw.validator.rules;

import com.anthem.pagw.core.model.PagwMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AttachmentValidationRule.
 * 
 * Implementation notes:
 * - Attachments have 'type' field: "Binary" or "DocumentReference"
 * - Binary attachments have 'data' field (base64 encoded)
 * - DocumentReference attachments have 'url' field
 * - Size is estimated from base64 data length for Binary type
 */
class AttachmentValidationRuleTest {

    private AttachmentValidationRule rule;
    private ObjectMapper mapper;
    private PagwMessage message;

    private static final int MAX_ATTACHMENT_COUNT = 20;

    @BeforeEach
    void setUp() {
        rule = new AttachmentValidationRule();
        mapper = new ObjectMapper();
        message = PagwMessage.builder()
                .pagwId("PAGW-TEST-001")
                .tenant("test-tenant")
                .build();
    }

    @Test
    void testIsApplicable_withAttachments() {
        ObjectNode data = mapper.createObjectNode();
        data.putArray("attachments").addObject();
        assertThat(rule.isApplicable(data, message)).isTrue();
    }

    @Test
    void testIsApplicable_withoutAttachments() {
        ObjectNode data = mapper.createObjectNode();
        assertThat(rule.isApplicable(data, message)).isFalse();
    }

    @Test
    void testIsApplicable_emptyAttachments() {
        ObjectNode data = mapper.createObjectNode();
        data.putArray("attachments"); // Empty array
        assertThat(rule.isApplicable(data, message)).isFalse();
    }

    @Test
    void testValidate_validAttachments() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode attachments = data.putArray("attachments");
        addBinaryAttachment(attachments, "application/pdf", "test.pdf", "SGVsbG8gV29ybGQh");
        addBinaryAttachment(attachments, "image/jpeg", "photo.jpg", "SGVsbG8gV29ybGQh");

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
    }

    // ===== Content Type Tests =====

    @Test
    void testValidate_suspiciousContentType() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode attachments = data.putArray("attachments");
        addBinaryAttachment(attachments, "application/x-executable", "test.exe", "SGVsbG8=");

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("SUSPICIOUS_CONTENT_TYPE"));
    }

    @Test
    void testValidate_unrecognizedContentType_warning() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode attachments = data.putArray("attachments");
        addBinaryAttachment(attachments, "application/custom-format", "test.custom", "SGVsbG8=");

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("UNRECOGNIZED_CONTENT_TYPE"));
    }

    @Test
    void testValidate_validContentTypes() {
        String[] validTypes = {
            "application/pdf",
            "application/xml",
            "application/json",
            "application/fhir+json",
            "text/plain",
            "text/xml",
            "image/jpeg",
            "image/png",
            "image/tiff"
        };

        for (String contentType : validTypes) {
            ObjectNode data = mapper.createObjectNode();
            ArrayNode attachments = data.putArray("attachments");
            addBinaryAttachment(attachments, contentType, "test.file", "SGVsbG8=");

            var result = rule.validate(data, message);

            assertThat(result.getErrors())
                    .noneMatch(e -> e.getCode().equals("SUSPICIOUS_CONTENT_TYPE"));
            assertThat(result.getWarnings())
                    .noneMatch(w -> w.getCode().equals("UNRECOGNIZED_CONTENT_TYPE"));
        }
    }

    @Test
    void testValidate_missingContentType_error() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode attachments = data.putArray("attachments");
        ObjectNode attachment = attachments.addObject();
        attachment.put("type", "Binary");
        attachment.put("title", "test.pdf");
        // No contentType

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("MISSING_CONTENT_TYPE"));
    }

    // ===== Size Tests =====

    @Test
    void testValidate_binaryAttachmentTooLarge() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode attachments = data.putArray("attachments");
        
        // Create base64 data > 10MB (10MB = 10,485,760 bytes)
        // Base64 is ~4/3 of original, so we need ~14MB of base64 to exceed 10MB
        String largeData = createLargeBase64(15 * 1024 * 1024); // 15MB of base64
        addBinaryAttachment(attachments, "application/pdf", "large.pdf", largeData);

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("ATTACHMENT_TOO_LARGE"));
    }

    @Test
    void testValidate_totalSizeTooLarge() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode attachments = data.putArray("attachments");
        
        // Add 6 attachments of ~9MB each to exceed 50MB total
        String data9mb = createLargeBase64(12 * 1024 * 1024); // ~9MB actual
        for (int i = 0; i < 6; i++) {
            addBinaryAttachment(attachments, "application/pdf", "file" + i + ".pdf", data9mb);
        }

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("TOTAL_SIZE_EXCEEDED"));
    }

    // ===== Count Tests =====

    @Test
    void testValidate_tooManyAttachments() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode attachments = data.putArray("attachments");
        
        for (int i = 0; i < MAX_ATTACHMENT_COUNT + 1; i++) {
            addBinaryAttachment(attachments, "application/pdf", "file" + i + ".pdf", "SGVsbG8=");
        }

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("TOO_MANY_ATTACHMENTS"));
    }

    @Test
    void testValidate_maxAttachmentsAllowed() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode attachments = data.putArray("attachments");
        
        for (int i = 0; i < MAX_ATTACHMENT_COUNT; i++) {
            addBinaryAttachment(attachments, "application/pdf", "file" + i + ".pdf", "SGVsbG8=");
        }

        var result = rule.validate(data, message);

        assertThat(result.getErrors())
                .noneMatch(e -> e.getCode().equals("TOO_MANY_ATTACHMENTS"));
    }

    // ===== DocumentReference URL Tests =====

    @Test
    void testValidate_documentReferenceWithValidUrl() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode attachments = data.putArray("attachments");
        addDocumentReference(attachments, "application/pdf", "test.pdf", "https://example.com/doc.pdf");

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void testValidate_documentReferenceWithBinaryUrl() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode attachments = data.putArray("attachments");
        addDocumentReference(attachments, "application/pdf", "test.pdf", "Binary/abc123");

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings())
                .noneMatch(w -> w.getCode().equals("INVALID_ATTACHMENT_URL"));
    }

    @Test
    void testValidate_documentReferenceMissingUrl() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode attachments = data.putArray("attachments");
        ObjectNode attachment = attachments.addObject();
        attachment.put("type", "DocumentReference");
        attachment.put("contentType", "application/pdf");
        attachment.put("title", "test.pdf");
        // No url

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("MISSING_ATTACHMENT_URL"));
    }

    @Test
    void testValidate_documentReferenceUnusualUrl_warning() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode attachments = data.putArray("attachments");
        addDocumentReference(attachments, "application/pdf", "test.pdf", "file:///local/path.pdf");

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("INVALID_ATTACHMENT_URL"));
    }

    // ===== Title Tests =====

    @Test
    void testValidate_missingTitle_warning() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode attachments = data.putArray("attachments");
        ObjectNode attachment = attachments.addObject();
        attachment.put("type", "Binary");
        attachment.put("contentType", "application/pdf");
        attachment.put("data", "SGVsbG8=");
        // No title

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("MISSING_ATTACHMENT_TITLE"));
    }

    @Test
    void testGetName() {
        assertThat(rule.getName()).isEqualTo("ATTACHMENT_VALIDATION");
    }

    @Test
    void testGetPriority() {
        assertThat(rule.getPriority()).isEqualTo(60);
    }

    // ===== Helper Methods =====

    private void addBinaryAttachment(ArrayNode attachments, String contentType, String title, String base64Data) {
        ObjectNode attachment = attachments.addObject();
        attachment.put("type", "Binary");
        attachment.put("contentType", contentType);
        attachment.put("title", title);
        attachment.put("data", base64Data);
    }

    private void addDocumentReference(ArrayNode attachments, String contentType, String title, String url) {
        ObjectNode attachment = attachments.addObject();
        attachment.put("type", "DocumentReference");
        attachment.put("contentType", contentType);
        attachment.put("title", title);
        attachment.put("url", url);
    }

    private String createLargeBase64(int length) {
        // Create a simple repeating string for testing
        StringBuilder sb = new StringBuilder(length);
        String pattern = "ABCD"; // Valid base64 characters
        while (sb.length() < length) {
            sb.append(pattern);
        }
        return sb.substring(0, length);
    }
}
