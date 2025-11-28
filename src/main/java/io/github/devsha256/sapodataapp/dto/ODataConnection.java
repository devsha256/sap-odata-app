package io.github.devsha256.sapodataapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Minimal connection DTO.
 */
public record ODataConnection(
        @NotBlank String url,
        @NotBlank String username,
        @NotBlank String password
) { }
