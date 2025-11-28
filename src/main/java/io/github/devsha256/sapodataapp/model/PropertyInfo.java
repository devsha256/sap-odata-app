package io.github.devsha256.sapodataapp.model;

import java.util.Optional;

/**
 * A small record describing a property found in metadata.
 */
public record PropertyInfo(String name, String type, Optional<Integer> maxLength) { }
