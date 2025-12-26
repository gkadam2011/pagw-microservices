package com.anthem.pagw.parser.controller;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.parser.model.ParseResult;
import com.anthem.pagw.parser.service.RequestParserService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for Request Parser - supports synchronous parsing calls.
 * 
 * This controller provides HTTP endpoints for orchestrator's synchronous path.
 * Primary flow is via SQS (RequestParserListener), but this enables
 * 15-second response requirement for Da Vinci PAS compliance.
 */
@RestController
@RequestMapping("/pas/api/v1")
public class ParserController {

    private static final Logger log = LoggerFactory.getLogger(ParserController.class);
    
    private final RequestParserService parserService;
    
    public ParserController(RequestParserService parserService) {
        this.parserService = parserService;
    }
    
    /**
     * Parse FHIR bundle synchronously.
     * 
     * Expected request body:
     * {
     *   "pagwId": "PAGW-xxx",
     *   "tenant": "tenant-id",
     *   "fhirBundle": "{ ... FHIR JSON ... }"
     * }
     * 
     * @param requestBody The parse request
     * @return ParseResult with success/errors
     */
    @PostMapping(
            value = "/parse",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Map<String, Object>> parse(@RequestBody String requestBody) {
        try {
            JsonNode request = JsonUtils.parseJson(requestBody);
            
            String pagwId = request.path("pagwId").asText();
            String tenant = request.path("tenant").asText(null);
            String fhirBundle = request.path("fhirBundle").asText();
            
            log.info("Synchronous parse request: pagwId={}, tenant={}", pagwId, tenant);
            
            // Build minimal PagwMessage for parsing context
            PagwMessage message = PagwMessage.builder()
                    .pagwId(pagwId)
                    .tenant(tenant)
                    .build();
            
            // Parse the bundle
            ParseResult result = parserService.parse(fhirBundle, message);
            
            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isValid());
            response.put("pagwId", pagwId);
            
            if (result.isValid()) {
                response.put("parsedBundle", JsonUtils.toJson(result.getParsedData()));
                response.put("hasAttachments", result.hasAttachments());
                response.put("attachmentCount", result.getAttachmentCount());
                log.info("Parse successful: pagwId={}, attachments={}", pagwId, result.getAttachmentCount());
            } else {
                response.put("errors", result.getErrors());
                log.warn("Parse failed: pagwId={}, errors={}", pagwId, result.getErrors());
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Parse error", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("errors", new String[]{"Parse error: " + e.getMessage()});
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }
    
    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "pasrequestparser"));
    }
}
