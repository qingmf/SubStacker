package com.substacker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscriptionTemplate(
    String id,
    String name,
    String url
) {
  public SubscriptionTemplate {
    id = id == null ? null : id.trim();
    name = name == null ? "" : name.trim();
    url = url == null ? "" : url.trim();
  }
}
