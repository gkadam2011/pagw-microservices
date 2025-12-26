package com.anthem.pagw.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * Workflow definition loaded from workflow.json.
 * Defines the processing stages for different request types.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowDefinition {

    private String workflowId;
    private String version;
    private String description;
    private FlowConfig syncFlow;
    private FlowConfig asyncFlow;
    private Map<String, Object> config;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FlowConfig {
        private List<StageDef> stages;
        private Map<String, Object> config;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StageDef {
        private String name;
        private String type;
        private String description;
        private String queue;
        private String service;
        private int timeoutSeconds;
        private int maxRetries;
        private String nextStage;
        private String errorStage;
        private Map<String, Object> config;
    }
}
