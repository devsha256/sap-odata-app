package io.github.devsha256.sapodataapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultHttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.HttpClientAccessor;
import com.sap.cloud.sdk.cloudplatform.connectivity.HttpDestination;
import io.github.devsha256.sapodataapp.dto.ODataConnection;
import io.github.devsha256.sapodataapp.dto.ODataQueryRequest;
import io.github.devsha256.sapodataapp.model.PropertyInfo;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service that handles metadata and query requests using SAP Cloud SDK HTTP destination.
 * Uses Apache HttpClient v4 types since HttpClientAccessor returns v4 client.
 */
@Service
public class ODataGatewayService {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Fetches metadata from the OData service and extracts a map of EntitySet -> required properties.
     * Required properties are considered those with Nullable="false".
     */
    public Map<String, List<PropertyInfo>> fetchMetadata(ODataConnection conn) {
        try {
            var destination = buildDestination(conn.url(), conn.username(), conn.password());
            HttpClient client = HttpClientAccessor.getHttpClient(destination);

            String metadataUrl = normalizeBaseUrl(conn.url());
            if (!metadataUrl.endsWith("/")) metadataUrl = metadataUrl + "/";
            metadataUrl = metadataUrl + "$metadata";

            HttpGet get = new HttpGet(URI.create(metadataUrl));
            try (CloseableHttpResponse response = (CloseableHttpResponse) client.execute(get)) {
                int status = response.getStatusLine().getStatusCode();
                if (status >= 400) {
                    throw new RuntimeException("Failed to fetch metadata: HTTP " + status);
                }
                InputStream content = response.getEntity().getContent();
                String xml = new String(content.readAllBytes());
                return parseMetadataXml(xml);
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to fetch or parse metadata: " + e.getMessage(), e);
        }
    }

    /**
     * Query an entity set with optional query options. Returns a list of generic maps (records).
     */
    public List<Map<String, Object>> queryEntitySet(ODataQueryRequest req) {
        try {
            var destination = buildDestination(req.url(), req.username(), req.password());
            HttpClient client = HttpClientAccessor.getHttpClient(destination);

            String base = normalizeBaseUrl(req.url());
            if (!base.endsWith("/")) base = base + "/";
            String target = base + encodeSegment(req.entitySet());
            String query = req.queryOptions() == null ? "" : (req.queryOptions().startsWith("?") ? req.queryOptions() : "?" + req.queryOptions());

            HttpGet get = new HttpGet(URI.create(target + query));
            try (CloseableHttpResponse response = (CloseableHttpResponse) client.execute(get)) {
                int status = response.getStatusLine().getStatusCode();
                if (status >= 400) {
                    throw new RuntimeException("Request failed with HTTP " + status);
                }
                InputStream content = response.getEntity().getContent();
                JsonNode root = mapper.readTree(content);

                // OData v4: { "value": [ ... ] }
                if (root.has("value") && root.get("value").isArray()) {
                    return mapper.convertValue(root.get("value"), new TypeReference<List<Map<String, Object>>>() {});
                }

                // OData v2: { "d": { "results": [ ... ] } }
                if (root.has("d")) {
                    JsonNode d = root.get("d");
                    if (d.has("results") && d.get("results").isArray()) {
                        return mapper.convertValue(d.get("results"), new TypeReference<List<Map<String, Object>>>() {});
                    }
                    if (d.isArray()) {
                        return mapper.convertValue(d, new TypeReference<List<Map<String, Object>>>() {});
                    }
                }

                if (root.isArray()) {
                    return mapper.convertValue(root, new TypeReference<List<Map<String, Object>>>() {});
                }

                if (root.isObject()) {
                    var map = mapper.convertValue(root, new TypeReference<Map<String,Object>>() {});
                    return Collections.singletonList(map);
                }

                return Collections.emptyList();
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to query entity set: " + e.getMessage(), e);
        }
    }

    // --- helpers ---

    private HttpDestination buildDestination(String url, String user, String password) {
        try {
            return DefaultHttpDestination
                    .builder(URI.create(url))
                    .basicCredentials(user, password)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build destination: " + e.getMessage(), e);
        }
    }

    private String normalizeBaseUrl(String url) {
        return url == null ? "" : url.trim();
    }

    private String encodeSegment(String seg) {
        return seg.replace(" ", "%20");
    }

    private Map<String, List<PropertyInfo>> parseMetadataXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));

            Map<String, Element> entityTypeByQualifiedName = new HashMap<>();
            NodeList schemas = doc.getElementsByTagNameNS("*", "Schema");
            for (int i = 0; i < schemas.getLength(); i++) {
                Element schema = (Element) schemas.item(i);
                String namespace = schema.getAttribute("Namespace");
                NodeList entityTypes = schema.getElementsByTagNameNS("*", "EntityType");
                for (int j = 0; j < entityTypes.getLength(); j++) {
                    Element et = (Element) entityTypes.item(j);
                    String etName = et.getAttribute("Name");
                    String qualified = (namespace == null || namespace.isEmpty()) ? etName : namespace + "." + etName;
                    entityTypeByQualifiedName.put(qualified, et);
                }
            }

            Map<String, List<PropertyInfo>> result = new LinkedHashMap<>();
            NodeList entityContainers = doc.getElementsByTagNameNS("*", "EntityContainer");
            for (int i = 0; i < entityContainers.getLength(); i++) {
                Element container = (Element) entityContainers.item(i);
                NodeList entitySets = container.getElementsByTagNameNS("*", "EntitySet");
                for (int j = 0; j < entitySets.getLength(); j++) {
                    Element es = (Element) entitySets.item(j);
                    String esName = es.getAttribute("Name");
                    String entityType = es.getAttribute("EntityType");

                    Element et = entityTypeByQualifiedName.get(entityType);
                    if (et == null) {
                        String[] parts = entityType.split("\\.");
                        String rawName = parts[parts.length - 1];
                        Optional<Element> found = entityTypeByQualifiedName.values().stream()
                                .filter(e -> rawName.equals(e.getAttribute("Name")))
                                .findFirst();
                        et = found.orElse(null);
                    }

                    List<PropertyInfo> requiredProps = new ArrayList<>();
                    if (et != null) {
                        NodeList properties = et.getElementsByTagNameNS("*", "Property");
                        for (int k = 0; k < properties.getLength(); k++) {
                            Element prop = (Element) properties.item(k);
                            String propName = prop.getAttribute("Name");
                            String propType = prop.getAttribute("Type");
                            String nullable = prop.getAttribute("Nullable");
                            String maxLengthStr = prop.getAttribute("MaxLength");
                            Optional<Integer> maxLen = Optional.empty();
                            if (maxLengthStr != null && !maxLengthStr.isBlank()) {
                                try { maxLen = Optional.of(Integer.parseInt(maxLengthStr)); } catch (NumberFormatException ignored) {}
                            }
                            boolean required = "false".equalsIgnoreCase(nullable);
                            if (required) {
                                requiredProps.add(new PropertyInfo(propName, propType, maxLen));
                            }
                        }
                    }
                    result.put(esName, requiredProps);
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse metadata XML: " + e.getMessage(), e);
        }
    }
}
