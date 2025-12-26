# FHIR Extraction Implementation Plan (Round 3)

## Overview
Extract structured clinical and administrative data from FHIR bundles in the parser stage to enable intelligent downstream processing.

## Current State
- Parser validates FHIR bundle structure
- Stores raw JSON blob in S3 (`raw-requests/`)
- Downstream services receive S3 path but lack easy access to key fields
- Services work "blindly" without structured data

## Target State
- Parser extracts structured data from FHIR bundle
- Store both raw bundle AND parsed data in S3
- Pass structured data to all downstream services
- Enable smart validation, enrichment, and routing decisions

---

## Data Models to Create (pagwcore)

### 1. PatientInfo.java
```java
public class PatientInfo {
    private String memberId;           // From Patient.identifier
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
    private String gender;             // male, female, other, unknown
    private Address address;
    private List<ContactPoint> contacts; // phone, email
}
```

### 2. PractitionerInfo.java
```java
public class PractitionerInfo {
    private String npi;                // National Provider Identifier
    private String firstName;
    private String lastName;
    private String taxonomyCode;       // Healthcare Provider Taxonomy
    private String specialty;
    private List<String> qualifications;
}
```

### 3. OrganizationInfo.java
```java
public class OrganizationInfo {
    private String npi;                // Facility NPI
    private String name;
    private String taxonomyCode;
    private Address address;
    private List<ContactPoint> contacts;
    private String type;               // hospital, clinic, etc.
}
```

### 4. ClaimInfo.java
```java
public class ClaimInfo {
    private String claimId;            // From Claim.identifier
    private String bundleId;           // From Bundle.identifier
    private LocalDate serviceDate;
    private String claimType;          // professional, institutional, pharmacy
    private String billablePeriodStart;
    private String billablePeriodEnd;
    
    // Clinical data
    private List<DiagnosisCode> diagnosisCodes;  // ICD-10
    private List<ProcedureCode> procedureCodes;  // CPT/HCPCS
    
    // Financial
    private BigDecimal totalAmount;
    private String currency;           // USD
    
    // Supporting resources
    private List<String> attachmentIds;
    private String priority;           // stat, normal, deferred
}
```

### 5. DiagnosisCode.java
```java
public class DiagnosisCode {
    private String code;               // ICD-10 code
    private String display;
    private String system;             // http://hl7.org/fhir/sid/icd-10
    private int sequence;              // Primary, secondary, etc.
    private String type;               // principal, admitting, discharge
}
```

### 6. ProcedureCode.java
```java
public class ProcedureCode {
    private String code;               // CPT/HCPCS code
    private String display;
    private String system;             // http://www.ama-assn.org/go/cpt
    private int sequence;
    private BigDecimal chargeAmount;
    private Integer quantity;
    private LocalDate serviceDate;
}
```

### 7. ParsedFhirData.java (Container)
```java
public class ParsedFhirData {
    private String pagwId;
    private String tenant;
    private Instant parsedAt;
    
    private PatientInfo patient;
    private PractitionerInfo practitioner;
    private OrganizationInfo organization;
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

## Implementation Steps

### Step 1: Add HAPI FHIR Dependencies
**File**: `pagwcore/pom.xml`

```xml
<!-- HAPI FHIR -->
<dependency>
    <groupId>ca.uhn.hapi.fhir</groupId>
    <artifactId>hapi-fhir-structures-r4</artifactId>
    <version>6.8.0</version>
</dependency>
<dependency>
    <groupId>ca.uhn.hapi.fhir</groupId>
    <artifactId>hapi-fhir-validation-resources-r4</artifactId>
    <version>6.8.0</version>
</dependency>
```

### Step 2: Create FhirExtractionService (pagwcore)
**File**: `pagwcore/src/main/java/com/anthem/pagw/core/service/FhirExtractionService.java`

```java
@Service
public class FhirExtractionService {
    private final FhirContext fhirContext;
    
    public FhirExtractionService() {
        this.fhirContext = FhirContext.forR4();
    }
    
    /**
     * Extract structured data from FHIR Bundle
     */
    public ParsedFhirData extractFromBundle(String fhirJson, String pagwId, String tenant) {
        Bundle bundle = parseBundle(fhirJson);
        
        ParsedFhirData result = new ParsedFhirData();
        result.setPagwId(pagwId);
        result.setTenant(tenant);
        result.setParsedAt(Instant.now());
        
        // Extract resources
        result.setPatient(extractPatient(bundle));
        result.setPractitioner(extractPractitioner(bundle));
        result.setOrganization(extractOrganization(bundle));
        result.setClaim(extractClaim(bundle));
        
        // Calculate statistics
        result.setTotalDiagnosisCodes(result.getClaim().getDiagnosisCodes().size());
        result.setTotalProcedureCodes(result.getClaim().getProcedureCodes().size());
        result.setHasUrgentIndicator(isUrgent(result.getClaim()));
        
        return result;
    }
    
    private Bundle parseBundle(String fhirJson) {
        IParser parser = fhirContext.newJsonParser();
        return parser.parseResource(Bundle.class, fhirJson);
    }
    
    private PatientInfo extractPatient(Bundle bundle) {
        Patient patient = findResource(bundle, Patient.class);
        if (patient == null) return null;
        
        PatientInfo info = new PatientInfo();
        info.setMemberId(extractMemberId(patient));
        info.setFirstName(patient.getName().get(0).getGivenAsSingleString());
        info.setLastName(patient.getName().get(0).getFamily());
        info.setDateOfBirth(convertToLocalDate(patient.getBirthDate()));
        info.setGender(patient.getGender().toCode());
        
        return info;
    }
    
    private PractitionerInfo extractPractitioner(Bundle bundle) {
        Practitioner practitioner = findResource(bundle, Practitioner.class);
        if (practitioner == null) return null;
        
        PractitionerInfo info = new PractitionerInfo();
        info.setNpi(extractNpi(practitioner));
        info.setFirstName(practitioner.getName().get(0).getGivenAsSingleString());
        info.setLastName(practitioner.getName().get(0).getFamily());
        info.setTaxonomyCode(extractTaxonomy(practitioner));
        
        return info;
    }
    
    private ClaimInfo extractClaim(Bundle bundle) {
        Claim claim = findResource(bundle, Claim.class);
        if (claim == null) return null;
        
        ClaimInfo info = new ClaimInfo();
        info.setClaimId(claim.getIdentifier().get(0).getValue());
        info.setBundleId(bundle.getIdentifier().getValue());
        info.setClaimType(claim.getType().getCodingFirstRep().getCode());
        info.setPriority(claim.getPriority().getCodingFirstRep().getCode());
        
        // Extract diagnosis codes
        info.setDiagnosisCodes(extractDiagnosisCodes(claim));
        
        // Extract procedure codes from items
        info.setProcedureCodes(extractProcedureCodes(claim));
        
        // Calculate total
        info.setTotalAmount(calculateTotal(claim));
        
        return info;
    }
    
    private List<DiagnosisCode> extractDiagnosisCodes(Claim claim) {
        List<DiagnosisCode> codes = new ArrayList<>();
        
        for (Claim.DiagnosisComponent diagnosis : claim.getDiagnosis()) {
            DiagnosisCode dc = new DiagnosisCode();
            dc.setCode(diagnosis.getDiagnosisCodeableConcept().getCodingFirstRep().getCode());
            dc.setDisplay(diagnosis.getDiagnosisCodeableConcept().getCodingFirstRep().getDisplay());
            dc.setSystem(diagnosis.getDiagnosisCodeableConcept().getCodingFirstRep().getSystem());
            dc.setSequence(diagnosis.getSequence());
            
            codes.add(dc);
        }
        
        return codes;
    }
    
    private List<ProcedureCode> extractProcedureCodes(Claim claim) {
        List<ProcedureCode> codes = new ArrayList<>();
        
        for (Claim.ItemComponent item : claim.getItem()) {
            ProcedureCode pc = new ProcedureCode();
            pc.setCode(item.getProductOrService().getCodingFirstRep().getCode());
            pc.setDisplay(item.getProductOrService().getCodingFirstRep().getDisplay());
            pc.setSystem(item.getProductOrService().getCodingFirstRep().getSystem());
            pc.setSequence(item.getSequence());
            pc.setQuantity(item.getQuantity().getValue().intValue());
            pc.setChargeAmount(item.getNet().getValue());
            pc.setServiceDate(convertToLocalDate(item.getServicedDateType().getValue()));
            
            codes.add(pc);
        }
        
        return codes;
    }
    
    private String extractMemberId(Patient patient) {
        return patient.getIdentifier().stream()
            .filter(id -> "http://anthem.com/member-id".equals(id.getSystem()))
            .findFirst()
            .map(Identifier::getValue)
            .orElse(null);
    }
    
    private String extractNpi(Practitioner practitioner) {
        return practitioner.getIdentifier().stream()
            .filter(id -> "http://hl7.org/fhir/sid/us-npi".equals(id.getSystem()))
            .findFirst()
            .map(Identifier::getValue)
            .orElse(null);
    }
    
    private <T extends Resource> T findResource(Bundle bundle, Class<T> resourceType) {
        return bundle.getEntry().stream()
            .map(Bundle.BundleEntryComponent::getResource)
            .filter(resourceType::isInstance)
            .map(resourceType::cast)
            .findFirst()
            .orElse(null);
    }
}
```

### Step 3: Update RequestParserListener
**File**: `pasrequestparser/src/main/java/.../listener/RequestParserListener.java`

```java
@Service
public class RequestParserListener {
    private final FhirValidationService validationService;
    private final FhirExtractionService extractionService;  // NEW
    private final S3Service s3Service;
    private final RequestTrackerService trackerService;
    private final EventTrackerService eventTrackerService;
    private final OutboxService outboxService;
    
    @SqsListener(queues = "${pagw.aws.sqs.parser-queue}")
    public void handleMessage(String messageBody) {
        // ... existing code for PARSE_START ...
        
        // 1. Validate FHIR bundle
        ValidationResult validationResult = validationService.validate(fhirBundle);
        if (!validationResult.isSuccessful()) {
            // ... existing error handling ...
        }
        
        // 2. NEW: Extract structured data
        ParsedFhirData parsedData = extractionService.extractFromBundle(
            fhirBundle, pagwId, tenant
        );
        
        // 3. Store parsed data in S3
        String parsedDataPath = String.format("parsed-data/%s/%s-parsed.json", 
            tenant, pagwId);
        s3Service.putObject(parsedDataPath, JsonUtils.toJson(parsedData));
        parsedData.setParsedDataS3Path(parsedDataPath);
        parsedData.setRawBundleS3Path(message.getBundleS3Path());
        
        // 4. Update message with parsed data path
        PagwMessage nextMessage = message.toBuilder()
            .parsedDataS3Path(parsedDataPath)
            .attachmentCount(parsedData.getTotalAttachments())
            .build();
        
        // 5. Event tracking with rich metadata
        String metadata = String.format(
            "{\"diagnosisCount\":%d,\"procedureCount\":%d,\"hasUrgent\":%b,\"claimType\":\"%s\"}",
            parsedData.getTotalDiagnosisCodes(),
            parsedData.getTotalProcedureCodes(),
            parsedData.isHasUrgentIndicator(),
            parsedData.getClaim().getClaimType()
        );
        eventTrackerService.logStageComplete(
            pagwId, tenant, "PARSER", EventTracker.EVENT_PARSE_OK, duration, metadata
        );
        
        // ... route to validator ...
    }
}
```

### Step 4: Update PagwMessage (pagwcore)
**File**: `pagwcore/src/main/java/com/anthem/pagw/core/model/PagwMessage.java`

Add field:
```java
private String parsedDataS3Path;  // Path to extracted FHIR data
```

### Step 5: Update Downstream Services
All downstream services can now access structured data:

```java
// In BusinessValidatorListener, RequestEnricherListener, etc.
String parsedDataPath = message.getParsedDataS3Path();
if (parsedDataPath != null) {
    String json = s3Service.getObject(parsedDataPath);
    ParsedFhirData parsedData = JsonUtils.fromJson(json, ParsedFhirData.class);
    
    // Now you have structured access!
    String memberId = parsedData.getPatient().getMemberId();
    String practitionerNpi = parsedData.getPractitioner().getNpi();
    List<DiagnosisCode> diagnoses = parsedData.getClaim().getDiagnosisCodes();
}
```

---

## Use Cases Enabled

### 1. Business Validator
```java
// Check diagnosis code validity
for (DiagnosisCode dx : parsedData.getClaim().getDiagnosisCodes()) {
    if (!isValidIcd10(dx.getCode())) {
        errors.add("Invalid ICD-10 code: " + dx.getCode());
    }
}

// Check practitioner NPI format
String npi = parsedData.getPractitioner().getNpi();
if (npi == null || !npi.matches("\\d{10}")) {
    errors.add("Invalid NPI format");
}
```

### 2. Request Enricher
```java
// Lookup member eligibility
String memberId = parsedData.getPatient().getMemberId();
LocalDate dob = parsedData.getPatient().getDateOfBirth();
EligibilityResponse eligibility = eligibilityService.check(memberId, dob);

// Verify practitioner in network
String npi = parsedData.getPractitioner().getNpi();
ProviderInfo provider = providerDirectory.lookup(npi);
```

### 3. Decision Engine / Orchestrator
```java
// Route high-value claims
if (parsedData.getClaim().getTotalAmount().compareTo(new BigDecimal("10000")) > 0) {
    return DecisionResponse.routeToManualReview("High value claim");
}

// Fast-track urgent claims
if (parsedData.isHasUrgentIndicator()) {
    return DecisionResponse.routeToPayer(routing, Priority.URGENT);
}

// Route by diagnosis code
List<String> diagCodes = parsedData.getClaim().getDiagnosisCodes()
    .stream().map(DiagnosisCode::getCode).collect(Collectors.toList());
if (diagCodes.stream().anyMatch(code -> code.startsWith("Z"))) {
    routing.setTargetSystem("carelon");  // Behavioral health
}
```

### 4. Request Converter
```java
// Convert to payer-specific format
ParsedFhirData parsedData = getParsedData(message);

if ("carelon".equals(targetSystem)) {
    CarelonRequest request = new CarelonRequest();
    request.setMemberId(parsedData.getPatient().getMemberId());
    request.setProviderNpi(parsedData.getPractitioner().getNpi());
    request.setDiagnosisCodes(parsedData.getClaim().getDiagnosisCodes()
        .stream().map(DiagnosisCode::getCode).collect(Collectors.toList()));
}
```

---

## Testing Strategy

### Unit Tests
```java
@Test
void testExtractPatient() {
    String fhirBundle = loadSampleBundle("claim-with-patient.json");
    ParsedFhirData result = extractionService.extractFromBundle(fhirBundle, pagwId, tenant);
    
    assertNotNull(result.getPatient());
    assertEquals("M123456789", result.getPatient().getMemberId());
    assertEquals("John", result.getPatient().getFirstName());
    assertEquals("Doe", result.getPatient().getLastName());
    assertEquals(LocalDate.of(1980, 5, 15), result.getPatient().getDateOfBirth());
}

@Test
void testExtractDiagnosisCodes() {
    String fhirBundle = loadSampleBundle("claim-with-diagnoses.json");
    ParsedFhirData result = extractionService.extractFromBundle(fhirBundle, pagwId, tenant);
    
    assertEquals(3, result.getTotalDiagnosisCodes());
    assertEquals("E11.9", result.getClaim().getDiagnosisCodes().get(0).getCode());
    assertEquals("Type 2 diabetes mellitus", result.getClaim().getDiagnosisCodes().get(0).getDisplay());
}
```

### Integration Test
```java
@Test
void testParserExtractsAndStoresStructuredData() {
    // Send FHIR bundle to parser queue
    String fhirBundle = loadSampleBundle("complete-claim.json");
    sendToQueue(parserQueue, fhirBundle);
    
    // Wait for processing
    await().atMost(10, SECONDS).until(() -> s3Service.objectExists(parsedDataPath));
    
    // Verify parsed data in S3
    String parsedJson = s3Service.getObject(parsedDataPath);
    ParsedFhirData parsedData = JsonUtils.fromJson(parsedJson, ParsedFhirData.class);
    
    assertNotNull(parsedData.getPatient());
    assertNotNull(parsedData.getClaim());
    assertEquals(2, parsedData.getTotalDiagnosisCodes());
    assertEquals(5, parsedData.getTotalProcedureCodes());
}
```

---

## Sample FHIR Bundles for Testing

Store in `pasrequestparser/src/test/resources/fhir/`:
- `minimal-claim.json` - Patient + Practitioner + Claim (no diagnoses)
- `claim-with-diagnoses.json` - Includes 3 ICD-10 codes
- `claim-with-procedures.json` - Includes 5 CPT codes
- `urgent-claim.json` - Priority = stat
- `high-value-claim.json` - Total > $10,000
- `complete-claim.json` - All resources populated

---

## Event Tracking Enhancements

### Parser Metadata (Enhanced)
```json
{
  "diagnosisCount": 3,
  "procedureCount": 5,
  "hasUrgent": true,
  "claimType": "professional",
  "totalAmount": 15000.00,
  "practitionerNpi": "1234567890",
  "facilityNpi": "9876543210"
}
```

### Validator Metadata (New)
```json
{
  "errorCount": 2,
  "warningCount": 1,
  "validatedFields": ["patient.memberId", "practitioner.npi", "claim.diagnosisCodes"],
  "invalidFields": ["claim.totalAmount"]
}
```

### Enricher Metadata (New)
```json
{
  "sourcesUsed": "eligibility,providerDirectory",
  "eligibilityStatus": "active",
  "providerInNetwork": true,
  "memberPlanType": "HMO"
}
```

---

## Rollout Plan

### Phase 1: Core Extraction (Week 1)
- Create model classes in pagwcore
- Implement FhirExtractionService
- Add unit tests with sample bundles
- Deploy pagwcore 1.0.0-SNAPSHOT

### Phase 2: Parser Integration (Week 1)
- Update RequestParserListener
- Store parsed data in S3
- Update PagwMessage with parsedDataS3Path
- Integration tests

### Phase 3: Downstream Consumption (Week 2)
- Update Validator to use structured data
- Update Enricher to use structured data
- Update Decision Engine routing logic
- End-to-end testing

### Phase 4: Optimization (Week 2)
- Cache parsed data in memory
- Optimize S3 reads (batch operations)
- Add CloudWatch metrics for extraction performance

---

## Dependencies

**Maven (pagwcore/pom.xml)**:
```xml
<dependency>
    <groupId>ca.uhn.hapi.fhir</groupId>
    <artifactId>hapi-fhir-structures-r4</artifactId>
    <version>6.8.0</version>
</dependency>
<dependency>
    <groupId>ca.uhn.hapi.fhir</groupId>
    <artifactId>hapi-fhir-validation-resources-r4</artifactId>
    <version>6.8.0</version>
</dependency>
```

**No Infrastructure Changes Required** - uses existing S3 buckets and message flow.

---

## Success Metrics

- ✅ Parser successfully extracts patient, practitioner, claim data
- ✅ Parsed data stored in S3 with <100ms overhead
- ✅ Validator uses structured data for business rules
- ✅ Enricher lookups based on structured fields
- ✅ Decision Engine routes based on diagnosis codes / claim amount
- ✅ Event metadata includes structured field counts
- ✅ All tests pass with sample FHIR bundles

---

## Future Enhancements

1. **Coverage Extraction** - Extract insurance coverage details
2. **Supporting Documentation** - Link DocumentReference resources
3. **Prior Authorization History** - Extract related authorization references
4. **Medication Extraction** - For pharmacy claims
5. **Lab Results** - For diagnostic report claims
6. **Caching Layer** - Redis cache for frequently accessed parsed data
7. **GraphQL API** - Query parsed data without S3 reads

---

**Status**: Ready to implement
**Estimated Effort**: 2-3 hours coding + 1 hour testing
**Dependencies**: None (uses existing infrastructure)
**Risk**: Low (additive feature, doesn't break existing flow)
