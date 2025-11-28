package io.github.devsha256.sapodataapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Generic query payload.
 */
public record ODataQueryRequest(
        @NotBlank String url,
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String entitySet,
        String queryOptions // optional e.g. "$select=Name&$top=10" or "$filter=..."
) { }
