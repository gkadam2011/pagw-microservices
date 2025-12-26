package com.anthem.pagw.core;

import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Data;

/**
 * Configuration properties for PAGW Core.
 * Prefix: pagw
 */
@Data
@ConfigurationProperties(prefix = "pagw")
public class PagwProperties {

    private String applicationId = "pagw";
    private Aws aws = new Aws();
    private Database database = new Database();
    private Queues queues = new Queues();
    private Encryption encryption = new Encryption();
    private Outbox outbox = new Outbox();

    @Data
    public static class Aws {
        private String region = "us-east-1";
        private String accountId;
        private Sqs sqs = new Sqs();
        private S3 s3 = new S3();
        private Secrets secrets = new Secrets();
        private DynamoDb dynamodb = new DynamoDb();
        private Kms kms = new Kms();
    }

    @Data
    public static class Sqs {
        private boolean enabled = true;
    }

    @Data
    public static class S3 {
        private boolean enabled = true;
        /**
         * Primary bucket for all request lifecycle data (PHI).
         * Structure: {bucket}/{YYYYMM}/{pagwId}/request|response|attachments|callbacks/
         * Example: crln-pagw-dev-dataz-gbd-phi-useast2
         */
        private String requestBucket;
        /**
         * Bucket for audit logs and operational data (NO PHI).
         * Structure: {bucket}/{YYYYMM}/{YYYYMMDD}/{service}/{HH}/{pagwId}_{uuid}.json
         * Example: crln-pagw-dev-logz-nogbd-nophi-useast2
         */
        private String auditBucket;
    }
    
    /**
     * S3 path utilities for consistent folder structure.
     * 
     * ALL data for a single request is stored under ONE folder:
     *   {YYYYMM}/{pagwId}/
     * 
     * This structure enables:
     * - Single folder to view entire request lifecycle
     * - Easy retention/cleanup (delete one folder per request)
     * - Fewer S3 LIST operations for debugging
     * - Compliance audit trail in one location
     * 
     * Request bucket structure:
     *   {YYYYMM}/{pagwId}/request/raw.json          - Original FHIR Bundle
     *   {YYYYMM}/{pagwId}/request/parsed.json       - Parsed claim data
     *   {YYYYMM}/{pagwId}/request/validated.json    - Business validated data
     *   {YYYYMM}/{pagwId}/request/enriched.json     - Enriched with provider/eligibility
     *   {YYYYMM}/{pagwId}/request/canonical.json    - Converted to target format (X12 278)
     *   {YYYYMM}/{pagwId}/response/payer_raw.json   - Raw payer API response
     *   {YYYYMM}/{pagwId}/response/fhir.json        - Final FHIR ClaimResponse
     *   {YYYYMM}/{pagwId}/attachments/meta.json     - Attachment metadata
     *   {YYYYMM}/{pagwId}/attachments/{uuid}_{name} - Individual attachment files
     *   {YYYYMM}/{pagwId}/callbacks/{timestamp}.json - Callback events
     *   {YYYYMM}/{pagwId}/_manifest.json            - Request manifest/summary
     */
    public static class S3Paths {
        
        // ═══════════════════════════════════════════════════════════════
        // Request Processing Stages
        // ═══════════════════════════════════════════════════════════════
        
        /** Original FHIR Bundle as received */
        public static String raw(String pagwId) {
            return String.format("%s/%s/request/raw.json", getMonthPartition(), pagwId);
        }
        
        /** Parsed claim data extracted from FHIR Bundle */
        public static String parsed(String pagwId) {
            return String.format("%s/%s/request/parsed.json", getMonthPartition(), pagwId);
        }
        
        /** Business validated data with validation results */
        public static String validated(String pagwId) {
            return String.format("%s/%s/request/validated.json", getMonthPartition(), pagwId);
        }
        
        /** Enriched data with provider/eligibility info */
        public static String enriched(String pagwId) {
            return String.format("%s/%s/request/enriched.json", getMonthPartition(), pagwId);
        }
        
        /** Converted to target format (X12 278, etc.) */
        public static String canonical(String pagwId) {
            return String.format("%s/%s/request/canonical.json", getMonthPartition(), pagwId);
        }
        
        // ═══════════════════════════════════════════════════════════════
        // Response Stages
        // ═══════════════════════════════════════════════════════════════
        
        /** Raw response from payer API */
        public static String payerResponse(String pagwId) {
            return String.format("%s/%s/response/payer_raw.json", getMonthPartition(), pagwId);
        }
        
        /** Callback/normalized response from callback handler */
        public static String callbackResponse(String pagwId) {
            return String.format("%s/%s/response/callback.json", getMonthPartition(), pagwId);
        }
        
        /** Final FHIR ClaimResponse */
        public static String fhirResponse(String pagwId) {
            return String.format("%s/%s/response/fhir.json", getMonthPartition(), pagwId);
        }
        
        // ═══════════════════════════════════════════════════════════════
        // Attachments
        // ═══════════════════════════════════════════════════════════════
        
        /** Attachment metadata summary */
        public static String attachmentMeta(String pagwId) {
            return String.format("%s/%s/attachments/meta.json", getMonthPartition(), pagwId);
        }
        
        /** Individual attachment file */
        public static String attachment(String pagwId, String uuid, String filename) {
            return String.format("%s/%s/attachments/%s_%s", getMonthPartition(), pagwId, uuid, filename);
        }
        
        // ═══════════════════════════════════════════════════════════════
        // Callbacks & Events
        // ═══════════════════════════════════════════════════════════════
        
        /** Async callback event with timestamp */
        public static String callback(String pagwId, String timestamp) {
            return String.format("%s/%s/callbacks/%s.json", getMonthPartition(), pagwId, timestamp);
        }
        
        /** Request manifest/summary */
        public static String manifest(String pagwId) {
            return String.format("%s/%s/_manifest.json", getMonthPartition(), pagwId);
        }
        
        // ═══════════════════════════════════════════════════════════════
        // Audit Bucket (NO PHI - separate bucket for compliance)
        // ═══════════════════════════════════════════════════════════════
        
        public static String audit(String service, String pagwId, String uuid) {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            return String.format("%s/%s/%s/%02d/%s_%s.json", 
                getMonthPartition(), 
                now.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                service,
                now.getHour(),
                pagwId, 
                uuid);
        }
        
        // ═══════════════════════════════════════════════════════════════
        // Utilities
        // ═══════════════════════════════════════════════════════════════
        
        private static String getMonthPartition() {
            return java.time.LocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        }
        
        /** For historical lookups - generate path with specific month */
        public static String rawWithMonth(String yearMonth, String pagwId) {
            return String.format("%s/%s/request/raw.json", yearMonth, pagwId);
        }
        
        /** Get the base folder for a request (for listing all files) */
        public static String requestFolder(String pagwId) {
            return String.format("%s/%s/", getMonthPartition(), pagwId);
        }
    }

    @Data
    public static class Secrets {
        private boolean enabled = true;
        private String auroraSecretName;
    }

    @Data
    public static class DynamoDb {
        private boolean enabled = false;
        private String idempotencyTable = "pagw-idempotency";
    }
    
    @Data
    public static class Kms {
        private boolean enabled = true;
        private String phiKeyAlias = "alias/pagw-phi-field-dev";
        private String s3KeyAlias = "alias/pagw-s3-dev";
    }

    @Data
    public static class Database {
        private String host;
        private int port = 5432;
        private String name = "pagw";
        private String schema = "pagw";
    }

    @Data
    public static class Queues {
        private String requestParser = "pagw-queue-request-parser";
        private String attachmentHandler = "pagw-queue-attachment-handler";
        private String businessValidator = "pagw-queue-business-validator";
        private String requestEnricher = "pagw-queue-request-enricher";
        private String canonicalMapper = "pagw-queue-canonical-mapper";
        private String apiOrchestrator = "pagw-queue-api-orchestrator";
        private String callbackHandler = "pagw-queue-callback-handler";
        private String responseBuilder = "pagw-queue-response-builder";
        private String outboxPublisher = "pagw-queue-outbox-publisher";
        private String replay = "pagw-queue-replay";
    }
    
    @Data
    public static class Encryption {
        private boolean enabled = true;
        private boolean encryptPhiFields = true;
        // PHI fields to encrypt at rest
        private java.util.List<String> phiFields = java.util.Arrays.asList(
                "patient.name",
                "patient.birthDate",
                "patient.identifier",
                "patient.address",
                "patient.telecom",
                "subscriber.name",
                "subscriber.identifier",
                "provider.name"
        );
    }
    
    @Data
    public static class Outbox {
        private boolean enabled = true;
        private int batchSize = 50;
        private int maxRetries = 5;
        private int publishIntervalMs = 1000;
    }
    
    /**
     * Get KMS PHI key alias.
     */
    public String getKmsPhiKeyAlias() {
        return aws.getKms().getPhiKeyAlias();
    }
}
