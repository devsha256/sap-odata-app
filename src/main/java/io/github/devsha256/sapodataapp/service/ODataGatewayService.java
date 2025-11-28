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
import org.apache.http.HttpResponse;
import org.apache.http.auth.AUTH;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.net.ssl.SSLContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * OData gateway service with optional "insecure/trust-all TLS" mode for local testing.
 */
@Service
public class ODataGatewayService {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Toggle for using an insecure "trust-all" TLS HttpClient (dev-only).
     * Configure via application.properties: app.security.insecure-trust-all=true
     */
    private final boolean insecureTrustAll;

    public ODataGatewayService(@Value("${app.security.insecure-trust-all:false}") boolean insecureTrustAll) {
        this.insecureTrustAll = insecureTrustAll;
    }

    /**
     * Fetch metadata and parse required properties per entity set.
     */
    public Map<String, List<PropertyInfo>> fetchMetadata(ODataConnection conn) {
        try {
            // build destination (used for URL normalization or other metadata if needed)
            var destination = buildDestination(conn.url(), conn.username(), conn.password());
            HttpClient client = chooseHttpClient(destination, conn);

            String metadataUrl = normalizeBaseUrl(conn.url());
            if (!metadataUrl.endsWith("/")) metadataUrl = metadataUrl + "/";
            metadataUrl = metadataUrl + "$metadata";

            HttpGet get = new HttpGet(URI.create(metadataUrl));
            // when using insecure client we set Basic header manually
            if (insecureTrustAll) {
                addBasicAuth(get, conn.username(), conn.password());
            }

            try (CloseableHttpResponse response = (CloseableHttpResponse) (client instanceof CloseableHttpClient ? ((CloseableHttpClient) client).execute(get) : HttpClients.createDefault().execute(get))) {
                int status = response.getStatusLine().getStatusCode();
                if (status >= 400) {
                    throw new RuntimeException("Failed to fetch metadata: HTTP " + status);
                }
                InputStream content = response.getEntity().getContent();
                String xml = new String(content.readAllBytes(), StandardCharsets.UTF_8);
                return parseMetadataXml(xml);
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to fetch or parse metadata: " + e.getMessage(), e);
        }
    }

    /**
     * Query an entity set, handling OData V2/V4 shapes.
     */
    public List<Map<String, Object>> queryEntitySet(ODataQueryRequest req) {
        try {
            var destination = buildDestination(req.url(), req.username(), req.password());
            HttpClient client = chooseHttpClient(destination, new ODataConnection(req.url(), req.username(), req.password()));
            String base = normalizeBaseUrl(req.url());
            if (!base.endsWith("/")) base = base + "/";
            String target = base + encodeSegment(req.entitySet());
            String query = req.queryOptions() == null ? "" : (req.queryOptions().startsWith("?") ? req.queryOptions() : "?" + req.queryOptions());

            HttpGet get = new HttpGet(URI.create(target + query));
            if (insecureTrustAll) {
                addBasicAuth(get, req.username(), req.password());
            }

            try (CloseableHttpResponse response = (CloseableHttpResponse) (client instanceof CloseableHttpClient ? ((CloseableHttpClient) client).execute(get) : HttpClients.createDefault().execute(get))) {
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
        } catch (JsonMappingException jm) {
            throw new RuntimeException("Failed to map JSON: " + jm.getMessage(), jm);
        } catch (Exception e) {
            throw new RuntimeException("Unable to query entity set: " + e.getMessage(), e);
        }
    }

    // --- helpers ---

    private HttpClient chooseHttpClient(HttpDestination destination, ODataConnection conn) {
        if (insecureTrustAll) {
            // create and return an HTTP client that trusts all certs
            return createTrustAllClient();
        } else {
            // use SAP Cloud SDK provided http client (v4 HttpClient)
            return HttpClientAccessor.getHttpClient(destination);
        }
    }

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

    private void addBasicAuth(HttpGet get, String user, String pass) {
        if (user == null) user = "";
        if (pass == null) pass = "";
        String token = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        get.addHeader("Authorization", "Basic " + token);
    }

    /**
     * Create an Apache HTTP client (v4) that trusts all certificates and disables hostname verification.
     * WARNING: insecure. Use only for local testing.
     */
    private CloseableHttpClient createTrustAllClient() {
        try {
            TrustStrategy acceptingTrustStrategy = (cert, authType) -> true;
            SSLContext sslContext = SSLContextBuilder.create()
                    .loadTrustMaterial(null, acceptingTrustStrategy)
                    .build();

            return HttpClients.custom()
                    .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .setSSLContext(sslContext)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create trust-all HTTP client: " + e.getMessage(), e);
        }
    }

    /**
     * Parses metadata XML and extracts required properties (as before).
     */
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
