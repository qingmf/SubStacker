package com.substacker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Upstream(
    String id,
    String name,
    UpstreamType type,
    String url,
    boolean enabled,
    Map<String, String> headers
) {
}

