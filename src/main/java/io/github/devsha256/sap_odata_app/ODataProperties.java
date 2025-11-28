package io.github.devsha256.sap_odata_app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * * Maps the SAP OData service configuration from application.properties to a Java object.
 * This uses Spring Boot's {@code @ConfigurationProperties} to automatically bind properties
 * prefixed with 'sap.odata'.
 */
@Configuration
@ConfigurationProperties(prefix = "sap.odata")
public class ODataProperties {

    // The base URL for the OData service (e.g., .../sap/opu/odata/sap/API_SERVICE)
    private String serviceUrl;
    
    // The username for Basic Authentication
    private String username;
    
    // The password for Basic Authentication
    private String password;

    // Standard getters and setters for Spring Boot configuration binding
    public String getServiceUrl() { return serviceUrl; }
    public void setServiceUrl(String serviceUrl) { this.serviceUrl = serviceUrl; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}