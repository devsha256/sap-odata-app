package io.github.devsha256.sap_odata_app.request;

/**
 * DTO to carry details for an OData entity collection read request.
 * It extends ODataConnection to include connectivity information.
 */
public record ODataQueryRequest(
    String serviceUrl,
    String username,
    String password,
    
    // The name of the OData entity set to query (e.g., "BusinessPartnerSet")
    String entitySetName, 
    
    // An optional OData system query option string (e.g., "$filter=ID eq '123'&$top=1")
    String queryOptions) 
{
    // Records automatically handle constructor, equals, hashCode, and toString.
}