# FHIR Extracted Data Storage Architecture

**Version:** 1.0  
**Date:** December 25, 2025  
**Status:** Implemented (Round 3 - Phase 1)

---

## Overview

This document describes how FHIR-extracted data is stored in the PAGW system using a hybrid approach:
- **PostgreSQL Database**: Indexed metadata for fast queries (patient_member_id, provider_npi, diagnosis_codes)
- **AWS S3**: Complete extracted data in JSON format for detailed access

---

## 1. Database Storage: `pagw.request_tracker`

### Primary Table Schema

```sql
CREATE TABLE pagw.request_tracker (
    -- Core identifiers
    pagw_id                 VARCHAR(50) PRIMARY KEY,
    tenant                  VARCHAR(100),
    status                  VARCHAR(30) NOT NULL DEFAULT 'received',
    
    -- FHIR Extracted Metadata (populated after parsing)
    patient_member_id       VARCHAR(100),     -- From FHIR Patient.identifier
    provider_npi            VARCHAR(20),      -- From FHIR Practitioner.identifier (NPI)
    provider_tax_id         VARCHAR(20),      -- From FHIR Organization (future)
    diagnosis_codes         JSONB,            -- Array of ICD-10 codes from FHIR Claim.diagnosis
    
    -- S3 References
    raw_s3_bucket           VARCHAR(255),
    raw_s3_key              VARCHAR(500),     -- Path: 202512/PAGW-xxx/request/raw.json
    enriched_s3_bucket      VARCHAR(255),
    enriched_s3_key         VARCHAR(500),
    final_s3_bucket         VARCHAR(255),
    final_s3_key            VARCHAR(500),
    
    -- Other fields...
    source_system           VARCHAR(50),
    request_type            VARCHAR(20),
    last_stage              VARCHAR(50),
    contains_phi            BOOLEAN DEFAULT true,
    received_at             TIMESTAMP WITH TIME ZONE,
    completed_at            TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

### Indexes

```sql
-- Standard indexes
CREATE INDEX idx_request_tracker_status ON pagw.request_tracker(status);
CREATE INDEX idx_request_tracker_tenant ON pagw.request_tracker(tenant);
CREATE INDEX idx_request_tracker_patient ON pagw.request_tracker(patient_member_id);
CREATE INDEX idx_request_tracker_created ON pagw.request_tracker(created_at);

-- FHIR-specific indexes
CREATE INDEX idx_request_tracker_diagnosis_codes 
    ON pagw.request_tracker USING GIN (diagnosis_codes);  -- V006 migration
```

### Data Population Flow

1. **Raw FHIR Bundle Received** → Stored in S3 (`raw_s3_key`)
2. **FHIR Extraction** (in `pasrequestparser`):
   - `FhirExtractionService.extractFromBundle()` parses HAPI FHIR R4 Bundle
   - Extracts: Patient, Practitioner, Claim resources
3. **Database Updates**:
   - `RequestTrackerService.updateFhirMetadata()` → Sets `patient_member_id`, `provider_npi`
   - `RequestTrackerService.updateDiagnosisCodes()` → Sets `diagnosis_codes` JSONB
4. **S3 Storage**:
   - Complete parsed data saved to `parsed-data/{tenant}/{pagwId}-parsed.json`

---

## 2. S3 Storage Structure

### Bucket Configuration

**Primary PHI Bucket:**
```
crln-pagw-{env}-dataz-gbd-phi-useast2
```
- Stores: Raw FHIR bundles, parsed data, responses, attachments
- Encryption: KMS or AES256 (server-side)
- Lifecycle: 7-year retention for audit compliance

**Audit Bucket (No PHI):**
```
crln-pagw-{env}-logz-nogbd-nophi-useast2
```
- Stores: Event logs, metrics, operational data
- No PHI allowed

### Complete Folder Structure

```
crln-pagw-{env}-dataz-gbd-phi-useast2/
│
├── 202512/                                    # Month partition (YYYYMM)
│   ├── PAGW-20251225-123456/                  # Request ID (unique)
│   │   ├── request/
│   │   │   ├── raw.json                       # Original FHIR Bundle (from API)
│   │   │   ├── parsed.json                    # Parsed claim data (RequestParserService)
│   │   │   ├── fhir-extracted.json            # ⭐ FHIR extracted data (Patient, Practitioner, Claim)
│   │   │   ├── validated.json                 # Business validated data
│   │   │   ├── enriched.json                  # Enriched with provider/eligibility
│   │   │   └── canonical.json                 # Converted to X12 278
│   │   │
│   │   ├── response/
│   │   │   ├── payer_raw.json                 # Raw payer API response
│   │   │   └── fhir.json                      # Final FHIR ClaimResponse
│   │   │
│   │   ├── attachments/
│   │   │   ├── meta.json                      # Attachment metadata
│   │   │   └── {uuid}_{filename}             # Individual files (PDF, JPEG, etc.)
│   │   │
│   │   ├── callbacks/
│   │   │   └── {timestamp}.json               # Callback events
│   │   │
│   │   └── _manifest.json                     # Request summary
│   │
│   └── PAGW-20251225-123457/
│       └── ...
│
└── 202601/                                    # Next month
        └── ...
```

### Path Patterns

| **Purpose** | **S3 Path** | **Description** |
|---|---|---|
| Raw FHIR Bundle | `{YYYYMM}/{pagwId}/request/raw.json` | Original submission |
| Parsed Claim | `{YYYYMM}/{pagwId}/request/parsed.json` | Parser output |
| **Extracted FHIR** | `{YYYYMM}/{pagwId}/request/fhir-extracted.json` | ⭐ Complete extraction |
| Validated Data | `{YYYYMM}/{pagwId}/request/validated.json` | Post-validation |
| Enriched Data | `{YYYYMM}/{pagwId}/request/enriched.json` | With lookups |
| Canonical Format | `{YYYYMM}/{pagwId}/request/canonical.json` | X12 278 ready |
| Payer Response | `{YYYYMM}/{pagwId}/response/payer_raw.json` | Payer API result |
| Final Response | `{YYYYMM}/{pagwId}/response/fhir.json` | FHIR ClaimResponse |
| Attachments | `{YYYYMM}/{pagwId}/attachments/{uuid}_{name}` | Files |

---

## 3. FHIR Extracted Data Structure

### Storage Locations by Field

| **Data Field** | **DB Column** | **S3 File** | **Use Case** |
|---|---|---|---|
| **Patient Member ID** | `patient_member_id` | `{YYYYMM}/{pagwId}/request/fhir-extracted.json` | Fast member lookup |
| **Provider NPI** | `provider_npi` | `{YYYYMM}/{pagwId}/request/fhir-extracted.json` | Fast provider lookup |
| **Diagnosis Codes** | `diagnosis_codes` (JSONB) | `{YYYYMM}/{pagwId}/request/fhir-extracted.json` | Diagnosis queries |
| Patient Full Name | ❌ | ✅ | Display/reports only |
| Patient Address | ❌ | ✅ | Display/reports only |
| Patient Contact | ❌ | ✅ | Display/reports only |
| Procedure Codes | ❌ | ✅ | Detailed view only |
| Claim Total Amount | ❌ | ✅ | Detailed view only |
| Practitioner Name | ❌ | ✅ | Display/reports only |
| Practitioner Contact | ❌ | ✅ | Display/reports only |
| Urgency Flag | ❌ | ✅ | Decision engine |
| Service Dates | ❌ | ✅ | Detailed view only |

**Strategy:**
- ✅ **Database**: Frequently queried fields with indexes
- ✅ **S3**: Complete data for display, conversion, detailed analysis

---

## 4. Parsed FHIR Data JSON Format

**File:** `parsed-data/anthem/PAGW-20251225-123456-parsed.json`

```json
{
  "pagwId": "PAGW-20251225-123456",
  "tenant": "anthem",
  "parsedAt": "2025-12-25T19:22:15Z",
  
  "patient": {
    "memberId": "MBR123456789",
    "firstName": "John",
    "lastName": "Doe",
    "dateOfBirth": "1980-05-15",
    "gender": "male",
    "addressLine1": "123 Main St",
    "city": "Atlanta",
    "state": "GA",
    "postalCode": "30301",
    "country": "US",
    "phone": "+1-404-555-1234",
    "email": "john.doe@example.com"
  },
  
  "practitioner": {
    "npi": "1234567890",
    "firstName": "Jane",
    "lastName": "Smith",
    "taxonomyCode": "207Q00000X",
    "specialty": "Family Medicine",
    "phone": "+1-404-555-5678",
    "email": "dr.smith@clinic.com"
  },
  
  "claim": {
    "claimId": "CLM-2025-001",
    "bundleId": "BUNDLE-123",
    "serviceDate": "2025-12-20",
    "claimType": "professional",
    "billablePeriodStart": "2025-12-20",
    "billablePeriodEnd": "2025-12-20",
    
    "diagnosisCodes": [
      {
        "code": "E11.9",
        "display": "Type 2 diabetes mellitus without complications",
        "system": "http://hl7.org/fhir/sid/icd-10",
        "sequence": 1,
        "type": "principal"
      },
      {
        "code": "I10",
        "display": "Essential (primary) hypertension",
        "system": "http://hl7.org/fhir/sid/icd-10",
        "sequence": 2,
        "type": "admitting"
      }
    ],
    
    "procedureCodes": [
      {
        "code": "99213",
        "display": "Office visit - established patient, level 3",
        "system": "http://www.ama-assn.org/go/cpt",
        "sequence": 1,
        "chargeAmount": 150.00,
        "quantity": 1,
        "serviceDate": "2025-12-20"
      }
    ],
    
    "totalAmount": 150.00,
    "currency": "USD",
    "attachmentCount": 2,
    "priority": "normal",
    "use": "preauthorization"
  },
  
  "totalDiagnosisCodes": 2,
  "totalProcedureCodes": 1,
  "totalAttachments": 2,
  "hasUrgentIndicator": false,
  
  "rawBundleS3Path": "202512/PAGW-20251225-123456/request/raw.json",
  "parsedDataS3Path": "parsed-data/anthem/PAGW-20251225-123456-parsed.json"
}
```

---

## 5. Query Patterns

### Database Queries (Fast Lookup)

**Find requests by patient:**
```sql
SELECT pagw_id, status, provider_npi, diagnosis_codes, created_at
FROM pagw.request_tracker 
WHERE patient_member_id = 'MBR123456789'
ORDER BY created_at DESC;
```

**Find requests by provider:**
```sql
SELECT pagw_id, patient_member_id, status, diagnosis_codes, created_at
FROM pagw.request_tracker 
WHERE provider_npi = '1234567890'
ORDER BY created_at DESC;
```

**Find requests by diagnosis code (exact match):**
```sql
SELECT pagw_id, patient_member_id, provider_npi, status
FROM pagw.request_tracker 
WHERE diagnosis_codes @> '[{"code": "E11.9"}]';
```

**Find requests with diabetes-related codes (pattern search):**
```sql
SELECT pagw_id, patient_member_id, status
FROM pagw.request_tracker 
WHERE diagnosis_codes::text LIKE '%E11%';
```

**Find requests with principal diagnosis:**
```sql
SELECT pagw_id, patient_member_id, diagnosis_codes
FROM pagw.request_tracker 
WHERE diagnosis_codes @> '[{"type": "principal"}]';
```

**Provider activity by diagnosis (analytics):**
```sql
SELECT 
    provider_npi,
    dx->>'code' as diagnosis_code,
    dx->>'display' as diagnosis_name,
    COUNT(*) as request_count,
    COUNT(CASE WHEN status = 'COMPLETED' THEN 1 END) as completed_count
FROM pagw.request_tracker, 
     jsonb_array_elements(diagnosis_codes) as dx
WHERE provider_npi IS NOT NULL
  AND diagnosis_codes IS NOT NULL
GROUP BY provider_npi, dx->>'code', dx->>'display'
ORDER BY request_count DESC;
```

**Patient requests with diagnosis counts:**
```sql
SELECT 
    patient_member_id,
    COUNT(*) as total_requests,
    jsonb_array_length(diagnosis_codes) as diagnosis_count,
    status
FROM pagw.request_tracker
WHERE patient_member_id IS NOT NULL
GROUP BY patient_member_id, diagnosis_codes, status;
```

**Population statistics:**
```sql
SELECT 
    COUNT(*) as total_requests,
    COUNT(patient_member_id) as with_patient_id,
    COUNT(provider_npi) as with_provider_npi,
    COUNT(diagnosis_codes) as with_diagnosis_codes,
    ROUND(100.0 * COUNT(patient_member_id) / NULLIF(COUNT(*), 0), 2) as patient_id_pct,
    ROUND(100.0 * COUNT(provider_npi) / NULLIF(COUNT(*), 0), 2) as provider_npi_pct,
    ROUND(100.0 * COUNT(diagnosis_codes) / NULLIF(COUNT(*), 0), 2) as diagnosis_codes_pct,
    ROUND(AVG(jsonb_array_length(diagnosis_codes)), 2) as avg_diagnoses_per_request
FROM pagw.request_tracker;
```

---

### S3 Retrieval (Full Data Access)

**Java Example:**
```java
// Get complete parsed FHIR data from S3
String parsedJson = s3Service.getParsedData(
    requestBucket, 
    "parsed-data/anthem/PAGW-20251225-123456-parsed.json"
);

ParsedFhirData data = JsonUtils.fromJson(parsedJson, ParsedFhirData.class);

// Access patient information
PatientInfo patient = data.getPatient();
String fullName = patient.getFirstName() + " " + patient.getLastName();
String memberAddress = String.format("%s, %s, %s %s", 
    patient.getAddressLine1(), patient.getCity(), 
    patient.getState(), patient.getPostalCode());

// Access practitioner information
PractitionerInfo practitioner = data.getPractitioner();
String providerName = "Dr. " + practitioner.getLastName();
String specialty = practitioner.getSpecialty();

// Access claim details
ClaimInfo claim = data.getClaim();
BigDecimal totalAmount = claim.getTotalAmount();
List<DiagnosisCode> diagnoses = claim.getDiagnosisCodes();
List<ProcedureCode> procedures = claim.getProcedureCodes();

// Check urgency
boolean isUrgent = data.isHasUrgentIndicator();
if (isUrgent || "stat".equals(claim.getPriority())) {
    // Expedite processing
}
```

**Python Example (for lambdas):**
```python
import boto3
import json

s3 = boto3.client('s3')

# Get parsed FHIR data
response = s3.get_object(
    Bucket='crln-pagw-dev-dataz-gbd-phi-useast2',
    Key='parsed-data/anthem/PAGW-20251225-123456-parsed.json'
)
parsed_data = json.loads(response['Body'].read())

# Access fields
member_id = parsed_data['patient']['memberId']
provider_npi = parsed_data['practitioner']['npi']
diagnosis_codes = [dx['code'] for dx in parsed_data['claim']['diagnosisCodes']]
total_amount = parsed_data['claim']['totalAmount']
```

---

## 6. Data Model Classes

### Java Models (pagwcore)

**PatientInfo.java:**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientInfo {
    private String memberId;
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
    private String gender;
    private String addressLine1;
    private String city;
    private String state;
    private String postalCode;
    private String country;
    private String phone;
    private String email;
}
```

**PractitionerInfo.java:**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PractitionerInfo {
    private String npi;
    private String firstName;
    private String lastName;
    private String taxonomyCode;
    private String specialty;
    private String phone;
    private String email;
}
```

**DiagnosisCode.java:**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisCode {
    private String code;
    private String display;
    private String system;
    private Integer sequence;
    private String type; // principal, admitting, discharge
}
```

**ProcedureCode.java:**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcedureCode {
    private String code;
    private String display;
    private String system;
    private Integer sequence;
    private BigDecimal chargeAmount;
    private Integer quantity;
    private LocalDate serviceDate;
}
```

**ClaimInfo.java:**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimInfo {
    private String claimId;
    private String bundleId;
    private LocalDate serviceDate;
    private String claimType;
    private LocalDate billablePeriodStart;
    private LocalDate billablePeriodEnd;
    
    @Builder.Default
    private List<DiagnosisCode> diagnosisCodes = new ArrayList<>();
    
    @Builder.Default
    private List<ProcedureCode> procedureCodes = new ArrayList<>();
    
    private BigDecimal totalAmount;
    private String currency;
    private Integer attachmentCount;
    private String priority;
    private String use;
}
```

**ParsedFhirData.java:**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedFhirData {
    private String pagwId;
    private String tenant;
    private Instant parsedAt;
    
    // Core resources
    private PatientInfo patient;
    private PractitionerInfo practitioner;
    private ClaimInfo claim;
    
    // Statistics
    private int totalDiagnosisCodes;
    private int totalProcedureCodes;
    private int totalAttachments;
    private boolean hasUrgentIndicator;
    
    // S3 references
    private String rawBundleS3Path;
    private String parsedDataS3Path;
}
```

---

## 7. Service Integration

### RequestParserListener Integration

**Flow:**
1. Receives FHIR Bundle from orchestrator queue
2. Validates FHIR structure
3. **Extracts structured data:**
   ```java
   ParsedFhirData parsedFhirData = fhirExtractionService.extractFromBundle(
       rawBundle, pagwId, tenant
   );
   ```
4. **Stores to S3:**
   ```java
   String parsedDataS3Path = s3Service.putParsedData(
       bucket, tenant, pagwId, JsonUtils.toJson(parsedFhirData)
   );
   ```
5. **Updates database:**
   ```java
   // Patient and provider metadata
   trackerService.updateFhirMetadata(pagwId, 
       parsedFhirData.getPatient().getMemberId(),
       parsedFhirData.getPractitioner().getNpi()
   );
   
   // Diagnosis codes
   trackerService.updateDiagnosisCodes(pagwId, 
       JsonUtils.toJson(parsedFhirData.getClaim().getDiagnosisCodes())
   );
   ```
6. **Passes to downstream:**
   ```java
   PagwMessage businessMessage = PagwMessage.builder()
       .parsedDataS3Path(parsedDataS3Path)
       .build();
   ```

### Downstream Service Consumption

**Business Validator Example:**
```java
// Fast lookup from database
Optional<RequestTracker> tracker = trackerService.findByPagwId(pagwId);
List<DiagnosisCode> diagnosisCodes = parseDiagnosisCodes(tracker.get().getDiagnosisCodes());

// Validate diagnosis codes
for (DiagnosisCode dx : diagnosisCodes) {
    if (!icd10Service.isValid(dx.getCode())) {
        errors.add("Invalid ICD-10 code: " + dx.getCode());
    }
}
```

**Request Enricher Example:**
```java
// Get full parsed data from S3 when needed
String parsedJson = s3Service.getParsedData(bucket, message.getParsedDataS3Path());
ParsedFhirData data = JsonUtils.fromJson(parsedJson, ParsedFhirData.class);

// Enrich with additional data
String memberId = data.getPatient().getMemberId();
MemberInfo memberInfo = memberService.lookup(memberId);

String npi = data.getPractitioner().getNpi();
ProviderInfo providerInfo = providerService.lookup(npi);
```

---

## 8. Performance Considerations

### Database Query Performance

**Indexed Fields (Fast):**
- `patient_member_id` - B-tree index
- `provider_npi` - B-tree index  
- `diagnosis_codes` - GIN index (supports `@>`, `?`, `?&`, `?|` operators)

**Expected Query Times:**
- Lookup by patient_member_id: **< 10ms**
- Lookup by provider_npi: **< 10ms**
- JSONB diagnosis code exact match: **< 50ms**
- JSONB diagnosis code pattern search: **< 200ms** (depends on data volume)

### S3 Access Performance

**GetObject Latency:**
- Small files (< 100KB): **50-100ms**
- Medium files (100KB-1MB): **100-300ms**
- Large files (> 1MB): **300ms+**

**Optimization:**
- Use database for frequent queries
- Cache S3 data in application memory when processing single request
- Use parallel S3 reads for batch operations

### Storage Costs

**Database:**
- Estimated size per row: **2-5 KB** (with diagnosis_codes JSONB)
- 1M requests: **2-5 GB** database storage

**S3:**
- Average parsed JSON size: **10-50 KB**
- 1M requests: **10-50 GB** S3 storage
- With S3 Glacier transition (90 days): Significant cost savings

---

## 9. Security & Compliance

### PHI Protection

**Database:**
- Encrypted at rest (PostgreSQL encryption)
- Encrypted in transit (SSL/TLS)
- Row-level security (RLS) by tenant
- Audit logging for all data access

**S3:**
- Server-side encryption (KMS or AES256)
- Bucket policies restrict access to PAGW services only
- VPC endpoints for private access (no internet exposure)
- Object lock for immutability (audit trail)
- Lifecycle policies for retention management

### HIPAA Compliance

**Data Retention:**
- Raw FHIR bundles: **7 years**
- Parsed FHIR data: **7 years**
- Event logs: **7 years**

**Access Audit:**
- All S3 access logged to CloudTrail
- All database access logged to `audit_log` table
- Weekly compliance reports

---

## 10. Migration & Deployment

### V006 Migration (diagnosis_codes)

**Already Applied:**
```sql
ALTER TABLE pagw.request_tracker 
    ADD COLUMN IF NOT EXISTS diagnosis_codes JSONB;

CREATE INDEX IF NOT EXISTS idx_request_tracker_diagnosis_codes 
    ON pagw.request_tracker USING GIN (diagnosis_codes);
```

**Deployment Status:**
- ✅ Schema change committed
- ✅ Code deployed (RequestParserListener)
- ✅ Tested in local environment

### Rollout Plan

**Phase 1: Soft Launch (Current)**
- FHIR extraction runs in parser
- Database fields populated
- S3 storage active
- Non-blocking (failures logged but don't stop pipeline)

**Phase 2: Enable Queries**
- Update analytics dashboards to use database fields
- Create provider portal queries
- Enable diagnosis-based routing

**Phase 3: Full Integration**
- Update all downstream services to consume parsed data
- Deprecate redundant FHIR parsing in other services
- Performance monitoring

---

## 11. Troubleshooting

### Common Issues

**Missing diagnosis_codes:**
```sql
-- Check population rate
SELECT 
    COUNT(*) as total,
    COUNT(diagnosis_codes) as with_codes,
    ROUND(100.0 * COUNT(diagnosis_codes) / NULLIF(COUNT(*), 0), 2) as pct
FROM pagw.request_tracker;
```

**Missing parsed S3 files:**
```java
// Check if file exists
String parsedPath = "parsed-data/" + tenant + "/" + pagwId + "-parsed.json";
boolean exists = s3Service.doesObjectExist(bucket, parsedPath);
```

**JSONB query not using index:**
```sql
-- Force GIN index usage
SET enable_seqscan = OFF;
EXPLAIN ANALYZE 
SELECT * FROM pagw.request_tracker 
WHERE diagnosis_codes @> '[{"code": "E11.9"}]';
```

---

## 12. Future Enhancements

### Potential Additions (Not Currently Implemented)

**Database Columns:**
- `procedure_codes JSONB` - For procedure-based queries
- `claim_total_amount DECIMAL(12,2)` - For financial queries
- `is_urgent BOOLEAN` - For priority routing
- `service_date DATE` - For date-based analytics

**S3 Enhancements:**
- Parquet format for analytics (Amazon Athena queries)
- S3 Select for partial JSON retrieval
- CloudFront distribution for cached access

**Analytics:**
- Materialized views for common aggregations
- Time-series partitioning for historical analysis
- Data warehouse integration (Redshift/Snowflake)

---

## References

- **Code Location**: `pagwcore/service/FhirExtractionService.java`
- **Database Migrations**: `pasorchestrator/resources/db/migration/V001__pagw_schema.sql`, `V006__add_diagnosis_codes_jsonb.sql`
- **Implementation Guide**: `docs/FHIR_EXTRACTION_PLAN.md`
- **HAPI FHIR Documentation**: https://hapifhir.io/hapi-fhir/docs/
- **PostgreSQL JSONB**: https://www.postgresql.org/docs/current/datatype-json.html

---

**Document Version History:**
- v1.0 (2025-12-25): Initial version - Round 3 Phase 1 implementation
