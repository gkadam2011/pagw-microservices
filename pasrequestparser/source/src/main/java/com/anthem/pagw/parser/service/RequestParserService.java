package com.anthem.pagw.parser.service;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.parser.model.ParseResult;
import com.anthem.pagw.parser.model.ParsedClaim;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for parsing FHIR bundles.
 * Extracts claims, attachments, and validates structure.
 */
@Service
public class RequestParserService {

    private static final Logger log = LoggerFactory.getLogger(RequestParserService.class);

    /**
     * Parse a FHIR bundle and extract relevant data.
     * 
     * @param rawBundle The raw FHIR bundle JSON
     * @param message The PAGW message context
     * @return ParseResult containing parsed data and validation results
     */
    public ParseResult parse(String rawBundle, PagwMessage message) {
        ParseResult result = new ParseResult();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        try {
            // Check for null/empty input
            if (rawBundle == null || rawBundle.trim().isEmpty()) {
                errors.add("Bundle is null or empty");
                result.setValid(false);
                result.setErrors(errors);
                return result;
            }
            
            JsonNode bundle = JsonUtils.parseJson(rawBundle);
            
            // Validate bundle structure
            if (!validateBundleStructure(bundle, errors)) {
                result.setValid(false);
                result.setErrors(errors);
                return result;
            }
            
            // Extract and validate resource type
            String resourceType = bundle.path("resourceType").asText();
            if (!"Bundle".equals(resourceType)) {
                errors.add("Invalid resourceType: expected 'Bundle', got '" + resourceType + "'");
                result.setValid(false);
                result.setErrors(errors);
                return result;
            }
            
            // Validate bundle type (should be 'collection' for PAS)
            String bundleType = bundle.path("type").asText();
            if (!bundleType.isEmpty() && !"collection".equals(bundleType) && !"batch".equals(bundleType)) {
                warnings.add("Unexpected bundle type: '" + bundleType + "'. Expected 'collection' or 'batch' for PAS.");
            }
            
            // Parse bundle entries
            ArrayNode entries = (ArrayNode) bundle.path("entry");
            ParsedClaim parsedClaim = new ParsedClaim();
            List<Map<String, Object>> attachments = new ArrayList<>();
            boolean hasClaim = false;
            boolean hasPatient = false;
            boolean hasPractitioner = false;
            
            for (JsonNode entry : entries) {
                JsonNode resource = entry.path("resource");
                
                // Skip entries without resources
                if (resource.isMissingNode()) {
                    warnings.add("Bundle entry found without 'resource' element");
                    continue;
                }
                
                String entryType = resource.path("resourceType").asText();
                
                // Skip entries without resourceType
                if (entryType.isEmpty()) {
                    warnings.add("Resource found without 'resourceType'");
                    continue;
                }
                
                switch (entryType) {
                    case "Claim":
                        if (hasClaim) {
                            warnings.add("Multiple Claim resources found - using first one");
                        } else {
                            parsedClaim = parseClaim(resource);
                            hasClaim = true;
                        }
                        break;
                    case "Patient":
                        if (hasPatient) {
                            warnings.add("Multiple Patient resources found - using first one");
                        } else {
                            parsedClaim.setPatientData(parsePatient(resource));
                            hasPatient = true;
                        }
                        break;
                    case "Practitioner":
                        if (hasPractitioner) {
                            warnings.add("Multiple Practitioner resources found - using first one");
                        } else {
                            parsedClaim.setPractitionerData(parsePractitioner(resource));
                            hasPractitioner = true;
                        }
                        break;
                    case "Organization":
                        parsedClaim.setOrganizationData(parseOrganization(resource));
                        break;
                    case "DocumentReference":
                        attachments.add(parseDocumentReference(resource));
                        break;
                    case "Binary":
                        attachments.add(parseBinaryAttachment(resource));
                        break;
                    case "Coverage":
                        // Coverage resource - extract insurance info
                        log.debug("Found Coverage resource");
                        break;
                    case "ServiceRequest":
                        // ServiceRequest - common in prior auth
                        log.debug("Found ServiceRequest resource");
                        break;
                    default:
                        log.debug("Skipping resource type: {}", entryType);
                }
            }
            
            // Validate required resources
            if (!hasClaim) {
                errors.add("Bundle must contain a Claim resource for prior authorization");
                result.setValid(false);
                result.setErrors(errors);
                result.setWarnings(warnings);
                return result;
            }
            
            if (!hasPatient) {
                warnings.add("No Patient resource found - patient data may be incomplete");
            }
            
            if (!hasPractitioner) {
                warnings.add("No Practitioner resource found - provider data may be incomplete");
            }
            
            // Set parsed data
            parsedClaim.setPagwId(message.getPagwId());
            parsedClaim.setTenant(message.getTenant());
            parsedClaim.setAttachments(attachments);
            parsedClaim.setBundleType(bundleType);
            
            result.setValid(true);
            result.setParsedData(parsedClaim);
            result.setHasAttachments(!attachments.isEmpty());
            result.setAttachmentCount(attachments.size());
            result.setWarnings(warnings);
            
            log.info("Parsed bundle: pagwId={}, claimType={}, attachments={}, warnings={}", 
                    message.getPagwId(), parsedClaim.getClaimType(), attachments.size(), warnings.size());
            
        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            log.error("JSON parse error: {}", e.getMessage(), e);
            errors.add("Invalid JSON format: " + e.getOriginalMessage());
            result.setValid(false);
            result.setErrors(errors);
        } catch (Exception e) {
            log.error("Parse error: {}", e.getMessage(), e);
            errors.add("Parse exception: " + e.getMessage());
            result.setValid(false);
            result.setErrors(errors);
        }
        
        return result;
    }

    private boolean validateBundleStructure(JsonNode bundle, List<String> errors) {
        if (bundle == null || bundle.isMissingNode()) {
            errors.add("Bundle is null or empty");
            return false;
        }
        
        if (!bundle.has("resourceType")) {
            errors.add("Missing required field: resourceType");
            return false;
        }
        
        if (!bundle.has("entry") || !bundle.path("entry").isArray()) {
            errors.add("Missing or invalid 'entry' array");
            return false;
        }
        
        ArrayNode entries = (ArrayNode) bundle.path("entry");
        if (entries.isEmpty()) {
            errors.add("Bundle contains no entries");
            return false;
        }
        
        return true;
    }

    private ParsedClaim parseClaim(JsonNode claimNode) {
        ParsedClaim claim = new ParsedClaim();
        
        claim.setClaimId(claimNode.path("id").asText());
        claim.setClaimType(claimNode.path("type").path("coding").path(0).path("code").asText());
        claim.setUse(claimNode.path("use").asText());
        claim.setStatus(claimNode.path("status").asText());
        claim.setCreated(claimNode.path("created").asText());
        claim.setPriority(claimNode.path("priority").path("coding").path(0).path("code").asText());
        
        // Extract total
        if (claimNode.has("total")) {
            JsonNode totalValueNode = claimNode.path("total").path("value");
            claim.setTotalValue(totalValueNode.isMissingNode() ? null : totalValueNode.decimalValue());
            claim.setTotalCurrency(claimNode.path("total").path("currency").asText());
        }
        
        // Extract billing provider reference
        if (claimNode.has("provider")) {
            claim.setProviderReference(claimNode.path("provider").path("reference").asText());
        }
        
        // Extract patient reference
        if (claimNode.has("patient")) {
            claim.setPatientReference(claimNode.path("patient").path("reference").asText());
        }
        
        // Extract insurance info
        if (claimNode.has("insurance") && claimNode.path("insurance").isArray()) {
            JsonNode insurance = claimNode.path("insurance").path(0);
            claim.setInsuranceSequence(insurance.path("sequence").asInt());
            claim.setInsuranceFocal(insurance.path("focal").asBoolean());
            claim.setCoverageReference(insurance.path("coverage").path("reference").asText());
        }
        
        // Extract line items
        List<Map<String, Object>> items = new ArrayList<>();
        if (claimNode.has("item") && claimNode.path("item").isArray()) {
            for (JsonNode item : claimNode.path("item")) {
                Map<String, Object> lineItem = new HashMap<>();
                lineItem.put("sequence", item.path("sequence").asInt());
                lineItem.put("productOrService", item.path("productOrService").path("coding").path(0).path("code").asText());
                lineItem.put("quantity", item.path("quantity").path("value").asInt());
                
                JsonNode unitPriceNode = item.path("unitPrice").path("value");
                lineItem.put("unitPrice", unitPriceNode.isMissingNode() ? null : unitPriceNode.decimalValue());
                
                JsonNode netNode = item.path("net").path("value");
                lineItem.put("net", netNode.isMissingNode() ? null : netNode.decimalValue());
                
                items.add(lineItem);
            }
        }
        claim.setLineItems(items);
        
        return claim;
    }

    private Map<String, Object> parsePatient(JsonNode patient) {
        return Map.of(
                "id", patient.path("id").asText(),
                "identifier", extractIdentifiers(patient.path("identifier")),
                "name", extractName(patient.path("name")),
                "birthDate", patient.path("birthDate").asText(),
                "gender", patient.path("gender").asText(),
                "address", extractAddress(patient.path("address"))
        );
    }

    private Map<String, Object> parsePractitioner(JsonNode practitioner) {
        return Map.of(
                "id", practitioner.path("id").asText(),
                "identifier", extractIdentifiers(practitioner.path("identifier")),
                "name", extractName(practitioner.path("name")),
                "qualification", extractQualifications(practitioner.path("qualification"))
        );
    }

    private Map<String, Object> parseOrganization(JsonNode org) {
        return Map.of(
                "id", org.path("id").asText(),
                "identifier", extractIdentifiers(org.path("identifier")),
                "name", org.path("name").asText(),
                "type", org.path("type").path(0).path("coding").path(0).path("code").asText(),
                "address", extractAddress(org.path("address"))
        );
    }

    private Map<String, Object> parseDocumentReference(JsonNode docRef) {
        return Map.of(
                "type", "DocumentReference",
                "id", docRef.path("id").asText(),
                "status", docRef.path("status").asText(),
                "contentType", docRef.path("content").path(0).path("attachment").path("contentType").asText(),
                "url", docRef.path("content").path(0).path("attachment").path("url").asText(),
                "title", docRef.path("content").path(0).path("attachment").path("title").asText()
        );
    }

    private Map<String, Object> parseBinaryAttachment(JsonNode binary) {
        return Map.of(
                "type", "Binary",
                "id", binary.path("id").asText(),
                "contentType", binary.path("contentType").asText(),
                "data", binary.path("data").asText()
        );
    }

    private List<Map<String, String>> extractIdentifiers(JsonNode identifiers) {
        List<Map<String, String>> result = new ArrayList<>();
        if (identifiers.isArray()) {
            for (JsonNode id : identifiers) {
                result.add(Map.of(
                        "system", id.path("system").asText(),
                        "value", id.path("value").asText()
                ));
            }
        }
        return result;
    }

    private Map<String, String> extractName(JsonNode names) {
        if (names.isArray() && !names.isEmpty()) {
            JsonNode name = names.path(0);
            return Map.of(
                    "family", name.path("family").asText(),
                    "given", name.path("given").path(0).asText(),
                    "prefix", name.path("prefix").path(0).asText(""),
                    "suffix", name.path("suffix").path(0).asText("")
            );
        }
        return Map.of();
    }

    private List<Map<String, String>> extractAddress(JsonNode addresses) {
        List<Map<String, String>> result = new ArrayList<>();
        if (addresses.isArray()) {
            for (JsonNode addr : addresses) {
                result.add(Map.of(
                        "line", addr.path("line").path(0).asText(""),
                        "city", addr.path("city").asText(),
                        "state", addr.path("state").asText(),
                        "postalCode", addr.path("postalCode").asText(),
                        "country", addr.path("country").asText("")
                ));
            }
        }
        return result;
    }

    private List<Map<String, String>> extractQualifications(JsonNode qualifications) {
        List<Map<String, String>> result = new ArrayList<>();
        if (qualifications.isArray()) {
            for (JsonNode qual : qualifications) {
                result.add(Map.of(
                        "code", qual.path("code").path("coding").path(0).path("code").asText(),
                        "display", qual.path("code").path("coding").path(0).path("display").asText()
                ));
            }
        }
        return result;
    }
}
