package io.github.devsha256.sapodataapp.controller;

import io.github.devsha256.sapodataapp.dto.ODataMetadataRequest;
import io.github.devsha256.sapodataapp.dto.ODataQueryRequest;
import io.github.devsha256.sapodataapp.model.PropertyInfo;
import io.github.devsha256.sapodataapp.service.ODataGatewayService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/odata")
public class ODataController {

    private final ODataGatewayService gatewayService;

    public ODataController(ODataGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    /**
     * POST /api/odata/metadata
     * Request: { url, username, password, entitySet? }
     * If entitySet is present, returns only that entity's required properties.
     */
    @PostMapping("/metadata")
    public ResponseEntity<?> metadata(@RequestBody @Valid ODataMetadataRequest request) {
        try {
            Map<String, List<PropertyInfo>> metadata = gatewayService.fetchMetadata(request);
            return ResponseEntity.ok(metadata);
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", iae.getMessage()));
        } catch (ODataGatewayService.EntityNotFoundException enf) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", enf.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/odata/query
     * Request: { url, username, password, entitySet, queryOptions }
     * Response: JSON array of records
     */
    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody @Valid ODataQueryRequest request) {
        try {
            List<Map<String, Object>> rows = gatewayService.queryEntitySet(request);
            return ResponseEntity.ok(rows);
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", iae.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}
