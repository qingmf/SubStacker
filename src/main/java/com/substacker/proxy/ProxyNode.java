package com.substacker.proxy;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProxyNode(
    String name,
    Map<String, Object> clashProxy,
    String dedupKey
) {
}

