package io.github.devsha256.sap_odata_app.service;

import com.sap.cloud.sdk.cloudplatform.connectivity.AuthenticationType;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultHttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.HttpDestination;
import com.sap.cloud.sdk.datamodel.odata.client.ODataProtocol;
import com.sap.cloud.sdk.datamodel.odata.client.ODataService;
import com.sap.cloud.sdk.datamodel.odata.client.request.ODataRequestGeneric;
import com.sap.cloud.sdk.datamodel.odata.client.request.ODataRequestResultGeneric;
import com.sap.cloud.sdk.datamodel.odata.client.request.ODataUriFactory;
import com.sap.cloud.sdk.datamodel.odata.client.service.ODataServiceBuilder;
import com.sap.cloud.sdk.datamodel.odata.client.service.ODataServiceFactory;
import com.sap.cloud.sdk.datamodel.odata.metadata.EdmType;
import com.sap.cloud.sdk.datamodel.odata.metadata.ServiceMetadata;
import com.sap.cloud.sdk.datamodel.odata.metadata.ServiceMetadata.EntityType;
import com.sap.cloud.sdk.datamodel.odata.metadata.ServiceMetadata.Property;
import io.github.devsha256.sap_odata_app.request.ODataConnection;
import io.github.devsha256.sap_odata_app.request.ODataQueryRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ODataServiceExtractor {

    /**
     * Creates a generic HTTP destination for the SAP Cloud SDK using Basic Authentication.
     * @param connection The DTO containing URL, username, and password.
     * @return A configured HttpDestination object.
     */
    private HttpDestination createDestination(final ODataConnection connection) {
        return DefaultHttpDestination.builder(connection.serviceUrl())
            .authenticationType(AuthenticationType.BASIC)
            .basicCredentials(connection.username(), connection.password())
            .build();
    }

    /**
     * Extracts required property details (field name, type, max length) from the OData metadata.
     * @param connection Connection details for the OData service.
     * @return A map where the key is the Entity Type name and the value is a list of its required properties' details.
     * @throws Exception if fetching or parsing the metadata fails.
     */
    public Map<String, List<Map<String, String>>> extractRequiredMetadata(final ODataConnection connection) throws Exception {
        final HttpDestination destination = createDestination(connection);

        // Build a generic OData client
        final ODataService odataService = ODataServiceBuilder.create()
            .withEndpointUrl(connection.serviceUrl())
            // OData V4 is generally a safe default for modern SAP services
            .withProtocol(ODataProtocol.V4)
            .withHttpDestination(destination)
            .build();

        // 1. Fetch the service metadata (the EDMX XML document)
        final ODataRequestGeneric metadataRequest = ODataUriFactory
            .createUri(ODataProtocol.V4, "")
            .withMetadataUri()
            .toRequest(odataService.getEndpointUrl(), odataService.getProtocol());
        
        final ServiceMetadata metadata = new ODataServiceFactory()
            .getMetadata(metadataRequest.execute(destination));

        // 2. Process metadata using Java Streams (functional style)
        return metadata.getMetamodel()
            .map(metamodel -> metamodel.getDefinedEntityTypes().stream()
                // Filter for Entity Types that have an associated Entity Set
                .filter(entityType -> entityType.getEntitySet().isPresent())
                // Collect into a Map: Key = Entity Set Name, Value = List of Required Properties' details
                .collect(Collectors.toMap(
                    // Key Mapper: Use the Entity Set Name (e.g., "BusinessPartnerSet")
                    entityType -> entityType.getEntitySet().get().getName(),
                    // Value Mapper: Extract only the required properties details
                    this::extractRequiredPropertiesDetails
                )))
            .orElse(Map.of()); // Return an empty map if metamodel is not present
    }

    /**
     * Helper method to extract required properties and map them to a simple structure.
     * @param entityType The OData EntityType to analyze.
     * @return A list of maps, each representing a required property.
     */
    private List<Map<String, String>> extractRequiredPropertiesDetails(final EntityType entityType) {
        return entityType.getProperties().stream()
            // Filter: Keep only properties that are not nullable (i.e., required)
            .filter(property -> !property.isNullable())
            // Map: Transform the Property object into a simple Map for JSON serialization
            .map(property -> Map.of(
                "fieldName", property.getName(),
                "type", property.getType().map(EdmType::getName).orElse("N/A"),
                "maxLength", property.getMaxLength().map(Objects::toString).orElse("N/A")
            ))
            .toList();
    }
    
    /**
     * Extracts a list of real data entities from the OData service using a generic query.
     * @param queryRequest The DTO containing connection details, entity set, and OData query options.
     * @return A list of generic OData entities as JSON strings.
     * @throws Exception if the request fails.
     */
    public List<Map<String, Object>> extractRealData(final ODataQueryRequest queryRequest) throws Exception {
        final HttpDestination destination = createDestination(queryRequest);
        
        // Use the untyped (generic) OData client from SAP Cloud SDK for maximum flexibility
        // as we don't know the entity structure beforehand.
        
        // 1. Build the Generic OData Read Request
        final ODataRequestGeneric readRequest = ODataUriFactory
            // Use the provided entity set name
            .createUri(ODataProtocol.V4, queryRequest.entitySetName()) 
            .withResourcePath()
            // Apply the raw query options string ($filter, $top, $skip, $orderby)
            .withQueryString(queryRequest.queryOptions() == null ? "" : queryRequest.queryOptions())
            .toRequest(
                queryRequest.serviceUrl(), 
                ODataProtocol.V4 // Assuming V4 as default, can be extended to be dynamic
            );

        // 2. Execute the request
        final ODataRequestResultGeneric result = readRequest.execute(destination);

        // 3. Extract the list of entities (data records)
        return result.asList(Map.class);
    }
}