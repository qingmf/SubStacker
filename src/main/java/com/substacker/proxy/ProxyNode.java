package com.substacker.proxy;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProxyNode(
    String name,
    Map<String, Object> clashProxy,
    String dedupKey,
    String sourceGroupName,
    Integer sourceGroupId,
    String sourceProviderName
) {
  public ProxyNode(String name, Map<String, Object> clashProxy, String dedupKey) {
    this(name, clashProxy, dedupKey, null, null, null);
  }

  public ProxyNode withSource(String sourceGroupName, Integer sourceGroupId, String sourceProviderName) {
    return new ProxyNode(name, clashProxy, dedupKey, sourceGroupName, sourceGroupId, sourceProviderName);
  }
}
