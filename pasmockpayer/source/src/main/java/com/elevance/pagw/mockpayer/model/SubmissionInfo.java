package com.elevance.pagw.mockpayer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionInfo {
    private String deadline;
    private String submissionUrl;
    private String referenceNumber;
    private Integer maxFileSizeMB;
}
