package com.substacker.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DownloadCache(
    Map<String, CachedEntry> entries
) {
  public DownloadCache {
    entries = entries == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(entries);
  }

  public static DownloadCache empty() {
    return new DownloadCache(new ConcurrentHashMap<>());
  }

  @RegisterForReflection
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CachedEntry(
      String body,
      String contentType,
      long savedAt
  ) {
  }
}
