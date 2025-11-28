package io.github.devsha256.sap_odata_app.request;

/**
 * Data Transfer Object (DTO) to carry generic OData connection details 
 * in the POST request body.
 */
public record ODataConnection(
    String serviceUrl,
    String username,
    String password) {
    // Records automatically provide a compact way to create immutable data classes 
    // and replace traditional getters/setters (Java 16+ feature).
}