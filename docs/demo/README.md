# PAGW Demo Bundles

This directory contains FHIR bundles demonstrating all possible validation outcomes in the PAGW system.

## Bundle Categories

### ✅ Success Cases
- **`01-success-valid-bundle.json`** - Fully valid bundle that passes all validations

### ❌ Error Cases

#### Required Field Errors
- **`02-error-missing-claimid.json`** - Missing required claimId field
- **`03-error-missing-patient-data.json`** - Missing patient data

#### Reference Format Errors
- **`04-error-invalid-patient-ref.json`** - Patient reference doesn't start with "Patient/"
- **`05-error-invalid-provider-ref.json`** - Provider reference doesn't start with "Practitioner/" or "Organization/"
- **`06-error-invalid-coverage-ref.json`** - Coverage reference doesn't start with "Coverage/"

#### NPI Validation Errors
- **`07-error-invalid-practitioner-npi.json`** - Invalid practitioner NPI (fails Luhn checksum)
- **`08-error-invalid-organization-npi.json`** - Invalid organization NPI
- **`09-error-missing-npi.json`** - Missing NPI identifier

#### Claim Type Errors
- **`10-error-invalid-claim-type.json`** - Invalid claim type value

#### Date Errors
- **`11-error-future-date.json`** - Claim created date in the future
- **`12-error-invalid-date-format.json`** - Invalid date format

#### Amount Errors
- **`13-error-negative-amount.json`** - Negative claim amount
- **`14-error-negative-line-amount.json`** - Negative line item amount

### ⚠️ Warning Cases
- **`15-warning-old-claim.json`** - Claim more than 1 year old
- **`16-warning-high-value.json`** - Claim exceeds $1,000,000
- **`17-warning-total-mismatch.json`** - Line item total doesn't match claim total
- **`18-warning-missing-focal.json`** - Missing insurance focal flag
- **`19-warning-invalid-sequence.json`** - Invalid insurance sequence

### 🔥 Complex Cases
- **`20-multiple-errors.json`** - Bundle with multiple validation errors (similar to official HL7 bundle)
- **`21-errors-and-warnings.json`** - Bundle with both errors and warnings

## Valid Test NPIs

Use these valid NPIs that pass Luhn checksum validation:
- `1234567893` - Valid practitioner NPI
- `1245319599` - Valid organization NPI  
- `1538272900` - Valid individual NPI
- `1942496701` - Valid facility NPI

## How to Use

1. Submit bundles via the UI at `http://localhost:3000`
2. Or use curl:
```bash
curl -X POST http://localhost:8080/api/submit \
  -H "Content-Type: application/json" \
  -d @docs/demo/01-success-valid-bundle.json
```

3. Check the response for validation results:
   - **Success**: Returns ClaimResponse with approved status
   - **Errors**: Returns OperationOutcome with error details
   - **Warnings**: Included in successful response metadata

## Testing Checklist

- [ ] 01-success-valid-bundle → Should return approved ClaimResponse
- [ ] 02-error-missing-claimid → Should fail with REQUIRED_FIELD_MISSING
- [ ] 03-error-missing-patient-data → Should fail with PATIENT_DATA_MISSING
- [ ] 04-error-invalid-patient-ref → Should fail with INVALID_REFERENCE_FORMAT
- [ ] 05-error-invalid-provider-ref → Should fail with INVALID_REFERENCE_FORMAT
- [ ] 06-error-invalid-coverage-ref → Should fail with INVALID_COVERAGE_REF
- [ ] 07-error-invalid-practitioner-npi → Should fail with INVALID_NPI
- [ ] 08-error-invalid-organization-npi → Should fail with INVALID_NPI
- [ ] 09-error-missing-npi → Should fail with MISSING_NPI
- [ ] 10-error-invalid-claim-type → Should fail with INVALID_CLAIM_TYPE
- [ ] 11-error-future-date → Should fail with FUTURE_DATE
- [ ] 12-error-invalid-date-format → Should fail with INVALID_DATE_FORMAT
- [ ] 13-error-negative-amount → Should fail with NEGATIVE_AMOUNT
- [ ] 14-error-negative-line-amount → Should fail with NEGATIVE_LINE_AMOUNT
- [ ] 15-warning-old-claim → Should succeed with OLD_CLAIM warning
- [ ] 16-warning-high-value → Should succeed with HIGH_VALUE_CLAIM warning
- [ ] 17-warning-total-mismatch → Should succeed with TOTAL_MISMATCH warning
- [ ] 18-warning-missing-focal → Should succeed with MISSING_FOCAL_FLAG warning
- [ ] 19-warning-invalid-sequence → Should succeed with INVALID_INSURANCE_SEQUENCE warning
- [ ] 20-multiple-errors → Should fail with 5 errors
- [ ] 21-errors-and-warnings → Should fail with errors + warnings listed
