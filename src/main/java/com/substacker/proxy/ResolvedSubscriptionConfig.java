package com.substacker.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record ResolvedSubscriptionConfig(
    String id,
    Map<String, Object> rootTemplate,
    List<Map<String, Object>> proxyGroups,
    List<String> rules
) {
  public ResolvedSubscriptionConfig {
    rootTemplate = rootTemplate == null
        ? Map.of()
        : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(rootTemplate));
    proxyGroups = proxyGroups == null ? List.of() : List.copyOf(new ArrayList<>(proxyGroups));
    rules = rules == null ? List.of() : List.copyOf(new ArrayList<>(rules));
  }
}
