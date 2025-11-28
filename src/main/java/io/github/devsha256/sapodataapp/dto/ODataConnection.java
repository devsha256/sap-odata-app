package io.github.devsha256.sapodataapp.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Metadata request DTO. `entitySet` is optional; when provided, the service
 * returns metadata only for that entity set.
 */
public record ODataMetadataRequest(
        @NotBlank String url,
        @NotBlank String username,
        @NotBlank String password,
        String entitySet // optional
) { }
