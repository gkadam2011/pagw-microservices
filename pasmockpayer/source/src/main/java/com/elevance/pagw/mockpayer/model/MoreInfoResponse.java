package com.elevance.pagw.mockpayer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoreInfoResponse {
    private String responseCode;
    private String responseStatus;
    private String message;
    private List<RequiredDocument> requiredDocuments;
    private SubmissionInfo submissionInfo;
    private ContactInfo contactInfo;
    private String providerNpi;
    private String payerName;
    private String payerId;
    private String processedAt;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class RequiredDocument {
    private String documentType;
    private String description;
    private Boolean required;
    private List<String> acceptedFormats;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class SubmissionInfo {
    private String deadline;
    private String submissionUrl;
    private String referenceNumber;
    private Integer maxFileSizeMB;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class ContactInfo {
    private String phone;
    private String email;
    private String hours;
}
