package io.github.devsha256.sap_odata_app;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.sap.cloud.sdk.cloudplatform.connectivity.AuthenticationType;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultHttpDestination;
import com.sap.cloud.sdk.datamodel.odata.client.ODataProtocol;
import com.sap.cloud.sdk.datamodel.odata.client.ODataService;
import com.sap.cloud.sdk.datamodel.odata.client.request.ODataRequestGeneric;
import com.sap.cloud.sdk.datamodel.odata.client.request.ODataUriFactory;
import com.sap.cloud.sdk.datamodel.odata.client.service.ODataServiceBuilder;
import com.sap.cloud.sdk.datamodel.odata.client.service.ODataServiceFactory;
import com.sap.cloud.sdk.datamodel.odata.metadata.EdmType;
import com.sap.cloud.sdk.datamodel.odata.metadata.Metamodel;
import com.sap.cloud.sdk.datamodel.odata.metadata.ServiceMetadata;
import com.sap.cloud.sdk.datamodel.odata.metadata.ServiceMetadata.EntityType;
import com.sap.cloud.sdk.datamodel.odata.metadata.ServiceMetadata.Property;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@SpringBootApplication
public class SapOdataAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(SapOdataAppApplication.class, args);
    }

    /**
     * Define the main execution logic to run on application startup.
     * * @param properties The configuration properties injected by Spring Boot.
     * @return A CommandLineRunner bean.
     */
    @Bean
    public CommandLineRunner run(ODataProperties properties) {
        return args -> {
            System.out.println("--- SAP OData Metadata Extraction Utility ---");
            try {
                // 1. Setup the SAP Cloud SDK HTTP Destination
                final DefaultHttpDestination destination = DefaultHttpDestination.builder(properties.getServiceUrl())
                        .authenticationType(AuthenticationType.BASIC)
                        .basicCredentials(properties.getUsername(), properties.getPassword())
                        .build();

                // 2. Build a generic OData client to fetch the metadata. 
                //    We use OData V4 as a default, but V2 metadata works similarly.
                final ODataService odataService = ODataServiceBuilder.create()
                        .withEndpointUrl(properties.getServiceUrl())
                        .withProtocol(ODataProtocol.V4) 
                        .withHttpDestination(destination)
                        .build();
                
                // 3. Fetch the service metadata (the EDMX XML document)
                System.out.println("Fetching metadata from: " + properties.getServiceUrl() + "/$metadata");
                final ODataRequestGeneric metadataRequest = ODataUriFactory
                        .createUri(ODataProtocol.V4, "")
                        .withMetadataUri()
                        .to)Request(odataService.getEndpointUrl(), odataService.getProtocol());
                
                // The SAP Cloud SDK ServiceMetadata class handles the HTTP request and XML parsing
                final ServiceMetadata metadata = new ODataServiceFactory()
                        .getMetadata(metadataRequest.execute(destination));

                System.out.println("Metadata fetched successfully. Analyzing entity types...");
                
                // 4. Extract and process the data model using a functional style
                processMetadata(metadata);

            } catch (Exception e) {
                System.err.println("\nAn error occurred during OData metadata extraction:");
                System.err.println("Error: " + e.getMessage());
                // Handle specific errors like authentication failure or URL issues
                if (e.getMessage().contains("401")) {
                    System.err.println("Authentication failed. Check your username and password.");
                } else if (e.getMessage().contains("404") || e.getMessage().contains("Invalid destination URL")) {
                    System.err.println("Service URL is invalid. Check 'sap.odata.service-url'.");
                }
            }
        };
    }

    /**
     * Processes the OData service metadata to extract and print required fields for each Entity Type.
     * This uses Java Streams for a concise, functional approach.
     * * @param metadata The parsed OData ServiceMetadata object.
     */
    private void processMetadata(final ServiceMetadata metadata) {
        // Get the top-level Entity Data Model (EDM) container
        final Optional<Metamodel> optionalMetamodel = metadata.getMetamodel();

        // Use functional style (Optional.map, Optional.ifPresent) to safely access the metamodel
        optionalMetamodel.map(Metamodel::getDefinedEntityTypes)
            // Stream over all Entity Types defined in the service
            .ifPresent(entityTypes -> entityTypes.stream()
                // Sort by name for a cleaner output
                .sorted((e1, e2) -> e1.getName().compareTo(e2.getName()))
                // For each Entity Type, extract its required properties
                .forEach(entityType -> {
                    System.out.println("\n---------------------------------------------------");
                    System.out.printf("Entity Set: %s (Type: %s)\n", 
                        entityType.getEntitySet().map(ServiceMetadata.EntitySet::getName).orElse("N/A"),
                        entityType.getName());
                    System.out.println("---------------------------------------------------");

                    // Filter properties that are not nullable (i.e., required)
                    final List<Property> requiredProperties = entityType.getProperties().stream()
                        .filter(property -> ! property.isNullable()) // Check if the property is NOT nullable
                        .toList(); // Use toList() from Java 16+

                    if (requiredProperties.isEmpty()) {
                        System.out.println("--> No explicitly required fields (Nullable=false) found for this Entity Type.");
                    } else {
                        System.out.println("Required Fields (for creating test data):");
                        
                        // Functional mapping to format the output for each required field
                        requiredProperties.stream()
                            .map(property -> String.format("  - %-30s | Type: %-20s | MaxLength: %s", 
                                property.getName(), 
                                property.getType().map(EdmType::getName).orElse("N/A"),
                                property.getMaxLength().map(Objects::toString).orElse("N/A")))
                            .forEach(System.out::println);
                    }
                }));
    }
}