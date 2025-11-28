package io.github.devsha256.sap_odata_app.controller;

import io.github.devsha256.sap_odata_app.request.ODataConnection;
import io.github.devsha256.sap_odata_app.request.ODataQueryRequest;
import io.github.devsha256.sap_odata_app.service.ODataServiceExtractor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/odata")
public class ODataController {

    private final ODataServiceExtractor serviceExtractor;

    // Constructor injection of the service
    public ODataController(ODataServiceExtractor serviceExtractor) {
        this.serviceExtractor = serviceExtractor;
    }

    /**
     * Endpoint to extract OData metadata (required fields) on demand.
     * * @param connection The request body containing service URL, username, and password.
     * @return A map of EntitySet names to a list of their required fields.
     */
    @PostMapping("/metadata")
    public ResponseEntity<Object> extractMetadata(@RequestBody ODataConnection connection) {
        try {
            // Check if essential connection details are provided
            if (connection.serviceUrl() == null || connection.serviceUrl().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Service URL is required."));
            }
            
            // Delegate the core logic to the service layer
            final Map<String, List<Map<String, String>>> result = serviceExtractor.extractRequiredMetadata(connection);
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // Log the error (in a real application, use a logger like SLF4J)
            e.printStackTrace();
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to extract metadata: " + e.getMessage()));
        }
    }
    
    /**
     * Endpoint to extract real OData entities (data records) on demand.
     * * @param queryRequest The request body containing connection details, entity set name, and optional OData query options.
     * @return A list of data records as generic JSON objects (Maps).
     */
    @PostMapping("/query")
    public ResponseEntity<Object> extractData(@RequestBody ODataQueryRequest queryRequest) {
        try {
            // Check if essential query details are provided
            if (queryRequest.serviceUrl() == null || queryRequest.serviceUrl().isBlank() || 
                queryRequest.entitySetName() == null || queryRequest.entitySetName().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Service URL and Entity Set Name are required."));
            }
            
            // Delegate the core logic to the service layer
            final List<Map<String, Object>> result = serviceExtractor.extractRealData(queryRequest);
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to execute OData query: " + e.getMessage()));
        }
    }
}