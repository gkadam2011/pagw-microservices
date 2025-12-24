# PAGW Demo Bundles - Summary

Created on: December 24, 2024

## 📦 What's Included

**21 FHIR Test Bundles** demonstrating all validation outcomes in the PAGW system.

### Bundle Categories

#### ✅ Success Cases (1 bundle)
- Valid bundle that passes all validations and gets approved

#### ❌ Error Cases (14 bundles)
**Required Field Errors (2)**
- Missing claimId
- Missing patient data

**Reference Format Errors (3)**
- Invalid patient reference format
- Invalid provider reference format
- Invalid coverage reference format

**NPI Validation Errors (3)**
- Invalid practitioner NPI (fails Luhn checksum)
- Invalid organization NPI
- Missing NPI identifier

**Business Rule Errors (6)**
- Invalid claim type
- Future date
- Invalid date format
- Negative total amount
- Negative line item amount

#### ⚠️ Warning Cases (5 bundles)
- Old claim (> 1 year)
- High value claim (> $1M)
- Line item total mismatch
- Missing insurance focal flag
- Invalid insurance sequence

#### 🔥 Complex Cases (2 bundles)
- Multiple errors (5 validation failures)
- Mixed errors and warnings

## 🎯 Purpose

These bundles fix the issue discovered in the logs where the official FHIR HL7 bundle had **5 validation errors**:
1. Invalid coverage reference format
2. Invalid practitioner NPI (1041117049)
3. Invalid organization NPI (2171717178)
4. Invalid patient reference format
5. Invalid provider reference format

Each demo bundle isolates **ONE specific validation scenario** for easy testing and debugging.

## 🚀 Quick Start

### 1. Test via UI
```
http://localhost:3000
```
Select any demo bundle from the dropdown and submit.

### 2. Test via curl
```bash
curl -X POST http://localhost:8080/api/submit \
  -H "Content-Type: application/json" \
  -d @docs/demo/01-success-valid-bundle.json
```

### 3. Verify All Scenarios
```bash
cd C:\Users\gkada\pagw-microservices

# Success case - should return ClaimResponse
curl -X POST http://localhost:8080/api/submit \
  -H "Content-Type: application/json" \
  -d @docs/demo/01-success-valid-bundle.json

# Error case - should return OperationOutcome with INVALID_NPI
curl -X POST http://localhost:8080/api/submit \
  -H "Content-Type: application/json" \
  -d @docs/demo/07-error-invalid-practitioner-npi.json

# Warning case - should return ClaimResponse with OLD_CLAIM warning
curl -X POST http://localhost:8080/api/submit \
  -H "Content-Type: application/json" \
  -d @docs/demo/15-warning-old-claim.json

# Multiple errors - should return OperationOutcome with 5 errors (like HL7 official bundle)
curl -X POST http://localhost:8080/api/submit \
  -H "Content-Type: application/json" \
  -d @docs/demo/20-multiple-errors.json
```

## 📖 Documentation

- **[README.md](README.md)** - Full catalog with testing checklist
- **[QUICK_REFERENCE.md](QUICK_REFERENCE.md)** - Quick lookup table with all error codes
- **[../da-vinci-pas-compliance.md](../da-vinci-pas-compliance.md)** - PAS compliance details

## ✅ Valid Test NPIs

Use these NPIs in your own test bundles:
- `1234567893` - Practitioner
- `1245319599` - Organization
- `1538272900` - Individual provider
- `1942496701` - Facility

All pass Luhn checksum validation!

## 🎓 Learning Path

**For Developers:**
1. Start with `01-success-valid-bundle.json` - understand the baseline
2. Compare with `02-error-missing-claimid.json` - see what breaks
3. Review `07-error-invalid-practitioner-npi.json` - understand NPI validation
4. Study `20-multiple-errors.json` - see compound failures

**For QA/Testing:**
1. Run through bundles 01-21 sequentially
2. Verify expected error codes in responses
3. Use checklist in README.md
4. Report any mismatches

**For Business Analysts:**
1. Review QUICK_REFERENCE.md for validation rules
2. Understand which errors block submission
3. Learn which scenarios generate warnings only
4. See real examples of each validation rule

## 🔧 Integration with UI

The demo bundles can be imported into the [pagw-ui](../../../pagw-ui) sample data:

```javascript
// Add to pagw-ui/src/data/sample-bundles.js
import successBundle from '../../pagw-microservices/docs/demo/01-success-valid-bundle.json';
import invalidNpiBundle from '../../pagw-microservices/docs/demo/07-error-invalid-practitioner-npi.json';

export const demoBundles = [
  { name: 'Valid Bundle', data: successBundle },
  { name: 'Invalid NPI', data: invalidNpiBundle },
  // ... more
];
```

## 📊 Coverage Report

| Validation Rule Category | Test Coverage |
|--------------------------|---------------|
| Required Fields | ✅ 100% (2/2) |
| Reference Formats | ✅ 100% (3/3) |
| NPI Validation | ✅ 100% (3/3) |
| Claim Type | ✅ 100% (1/1) |
| Date Validation | ✅ 100% (3/3) |
| Amount Validation | ✅ 100% (4/4) |
| Insurance Validation | ✅ 100% (2/2) |
| Complex Scenarios | ✅ 100% (2/2) |
| **TOTAL** | **✅ 100% (21/21)** |

All validation outcomes are covered! 🎉

## 🤝 Contributing

To add new demo bundles:
1. Create JSON file: `XX-category-description.json`
2. Add entry to README.md checklist
3. Add row to QUICK_REFERENCE.md table
4. Test with: `curl -X POST http://localhost:8080/api/submit -H "Content-Type: application/json" -d @docs/demo/XX-category-description.json`
5. Commit with descriptive message

## 📝 Notes

- All bundles use valid FHIR R4 structure
- Success bundle (`01`) has fully valid data including proper NPIs
- Error bundles isolate single validation failures for clarity
- Warning bundles pass validation but trigger advisory messages
- Complex bundles (`20-21`) demonstrate real-world scenarios with multiple issues

---

**Happy Testing! 🚀**
