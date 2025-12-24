# Demo Bundle Quick Reference

## 📋 All Validation Outcomes

| # | Filename | Outcome | Error/Warning Code | Description |
|---|----------|---------|-------------------|-------------|
| 01 | `01-success-valid-bundle.json` | ✅ **SUCCESS** | - | Fully valid bundle, all validations pass |
| 02 | `02-error-missing-claimid.json` | ❌ **ERROR** | `REQUIRED_FIELD_MISSING` | Missing required claimId field |
| 03 | `03-error-missing-patient-data.json` | ❌ **ERROR** | `PATIENT_DATA_MISSING` | No Patient resource in bundle |
| 04 | `04-error-invalid-patient-ref.json` | ❌ **ERROR** | `INVALID_REFERENCE_FORMAT` | Patient ref = "patient-004" (missing "Patient/") |
| 05 | `05-error-invalid-provider-ref.json` | ❌ **ERROR** | `INVALID_REFERENCE_FORMAT` | Provider ref = "doctor-005" (missing "Practitioner/") |
| 06 | `06-error-invalid-coverage-ref.json` | ❌ **ERROR** | `INVALID_COVERAGE_REF` | Coverage ref = "insurance-006" (missing "Coverage/") |
| 07 | `07-error-invalid-practitioner-npi.json` | ❌ **ERROR** | `INVALID_NPI` | NPI 1041117049 fails Luhn checksum |
| 08 | `08-error-invalid-organization-npi.json` | ❌ **ERROR** | `INVALID_NPI` | Organization NPI 9999999999 fails Luhn |
| 09 | `09-error-missing-npi.json` | ❌ **ERROR** | `MISSING_NPI` | Practitioner has no NPI identifier |
| 10 | `10-error-invalid-claim-type.json` | ❌ **ERROR** | `INVALID_CLAIM_TYPE` | Claim type "automobile" not valid |
| 11 | `11-error-future-date.json` | ❌ **ERROR** | `FUTURE_DATE` | Created date "2026-12-24" is in future |
| 12 | `12-error-invalid-date-format.json` | ❌ **ERROR** | `INVALID_DATE_FORMAT` | Date "2024-13-45" is malformed |
| 13 | `13-error-negative-amount.json` | ❌ **ERROR** | `NEGATIVE_AMOUNT` | Total value -150.00 is negative |
| 14 | `14-error-negative-line-amount.json` | ❌ **ERROR** | `NEGATIVE_LINE_AMOUNT` | Line item net -50.00 is negative |
| 15 | `15-warning-old-claim.json` | ✅ **SUCCESS** | `OLD_CLAIM` ⚠️ | Created "2022-06-15" is > 1 year old |
| 16 | `16-warning-high-value.json` | ✅ **SUCCESS** | `HIGH_VALUE_CLAIM` ⚠️ | Total $1,250,000 exceeds $1M threshold |
| 17 | `17-warning-total-mismatch.json` | ✅ **SUCCESS** | `TOTAL_MISMATCH` ⚠️ | Line items ($175) ≠ total ($200) |
| 18 | `18-warning-missing-focal.json` | ✅ **SUCCESS** | `MISSING_FOCAL_FLAG` ⚠️ | Insurance missing focal flag |
| 19 | `19-warning-invalid-sequence.json` | ✅ **SUCCESS** | `INVALID_INSURANCE_SEQUENCE` ⚠️ | Insurance sequence = 0 (should be ≥ 1) |
| 20 | `20-multiple-errors.json` | ❌ **ERROR** | 5 errors | All reference format + NPI errors combined |
| 21 | `21-errors-and-warnings.json` | ❌ **ERROR** | 2 errors + 3 warnings | Missing NPI + patient data + warnings |

## 🧪 Testing Commands

### Via UI (Recommended)
1. Navigate to http://localhost:3000
2. Select a demo bundle from the dropdown
3. Click "Submit Request"
4. View validation results

### Via curl

```bash
# Test success case
curl -X POST http://localhost:8080/api/submit \
  -H "Content-Type: application/json" \
  -d @docs/demo/01-success-valid-bundle.json

# Test missing claimId error
curl -X POST http://localhost:8080/api/submit \
  -H "Content-Type: application/json" \
  -d @docs/demo/02-error-missing-claimid.json

# Test multiple errors (like official HL7 bundle)
curl -X POST http://localhost:8080/api/submit \
  -H "Content-Type: application/json" \
  -d @docs/demo/20-multiple-errors.json
```

### Via PowerShell

```powershell
# Test success case
$headers = @{"Content-Type"="application/json"}
$body = Get-Content docs/demo/01-success-valid-bundle.json -Raw
Invoke-RestMethod -Uri http://localhost:8080/api/submit -Method Post -Headers $headers -Body $body

# Test invalid NPI
$body = Get-Content docs/demo/07-error-invalid-practitioner-npi.json -Raw
Invoke-RestMethod -Uri http://localhost:8080/api/submit -Method Post -Headers $headers -Body $body
```

## 📊 Expected Response Formats

### ✅ Success Response
```json
{
  "resourceType": "ClaimResponse",
  "status": "active",
  "outcome": "complete",
  "pagwId": "PAGW-...",
  "warnings": [
    {
      "code": "OLD_CLAIM",
      "message": "Claim is more than 1 year old",
      "field": "created"
    }
  ]
}
```

### ❌ Error Response
```json
{
  "resourceType": "OperationOutcome",
  "pagwId": "PAGW-...",
  "status": "error",
  "message": "Validation failed",
  "validationErrors": [
    {
      "code": "INVALID_NPI",
      "severity": "error",
      "location": "practitionerData.identifier",
      "message": "Invalid NPI format for practitioner: 1041117049",
      "issueType": "value"
    }
  ]
}
```

## 🎯 Validation Rules Summary

### Required Fields
- `pagwId`, `claimId`, `claimType`
- `patientReference`, `providerReference`
- `patientData` (Patient resource must exist)

### Reference Formats
- Patient: Must start with `Patient/`
- Provider: Must start with `Practitioner/` or `Organization/`
- Coverage: Must start with `Coverage/`

### NPI Validation
- Must be exactly 10 digits
- Must pass Luhn algorithm checksum
- System must be `http://hl7.org/fhir/sid/us-npi` or `urn:oid:2.16.840.1.113883.4.6`

### Valid NPIs for Testing
- `1234567893` - Valid practitioner
- `1245319599` - Valid organization
- `1538272900` - Valid individual
- `1942496701` - Valid facility

### Claim Types (Valid Values)
- `professional`, `institutional`, `oral`, `vision`, `pharmacy`

### Date Validations
- **Error**: Future dates
- **Error**: Invalid format (must be ISO 8601)
- **Warning**: Dates > 1 year old

### Amount Validations
- **Error**: Negative total or line amounts
- **Warning**: Total > $1,000,000
- **Warning**: Line items don't sum to total

### Insurance Validations
- **Warning**: Missing focal flag
- **Warning**: Sequence < 1

## 🔍 Troubleshooting

### Bundle Not Found
- Ensure you're in the `pagw-microservices` directory
- Use relative path: `@docs/demo/01-success-valid-bundle.json`

### Services Not Running
```bash
# Check service status
curl http://localhost:8080/actuator/health
```

### Validation Not Triggering
- Check that bundle has proper FHIR structure
- Ensure `resourceType: "Bundle"` is present
- Verify all references exist in bundle

## 📚 Related Documentation
- [README.md](README.md) - Full bundle catalog
- [../da-vinci-pas-compliance.md](../da-vinci-pas-compliance.md) - PAS compliance details
- [../DEPLOYMENT.md](../DEPLOYMENT.md) - System deployment guide
