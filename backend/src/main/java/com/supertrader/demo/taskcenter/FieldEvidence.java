package com.supertrader.demo.taskcenter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Field-level evidence of a Draft: which conversation turn supplied the
 * confirmed value of a field (Module 9).
 *
 * <p>Every confirmed field of a Draft carries at least one FieldEvidence
 * record so the field's provenance is traceable (「已确认字段及字段来源」).
 * {@code value} is a short sanitised echo (at most 200 chars, no control
 * characters, never a credential-shaped value).
 */
public record FieldEvidence(
        @JsonProperty("field") String field,
        @JsonProperty("sourceTurnId") String sourceTurnId,
        @JsonProperty("value") String value,
        @JsonProperty("citedAt") String citedAt) {

    @JsonCreator
    public FieldEvidence {}
}
