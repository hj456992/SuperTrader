package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single field-level issue of a {@link ValidationReport} (Module 9).
 * {@code field} is the Draft field name (or a fixed group label such as
 * "content" / "dataset"), {@code code} a machine-readable rule id and
 * {@code message} a user-facing Chinese explanation.
 */
public record ValidationIssue(
        @JsonProperty("field") String field,
        @JsonProperty("code") String code,
        @JsonProperty("message") String message) {

    @JsonCreator
    public ValidationIssue {}
}
