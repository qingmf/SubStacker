package com.substacker.proxy;

import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class ClashYamlParser implements UpstreamParser {
  private final Yaml yaml = new Yaml();

  @Override
  public boolean supports(String contentType, String content) {
    if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("yaml")) {
      return true;
    }
    String trimmed = content == null ? "" : content.trim();
    return trimmed.startsWith("proxies:") || trimmed.contains("\nproxies:");
  }

  @Override
  public List<ProxyNode> parse(String upstreamName, String contentType, String content) {
    Objects.requireNonNull(content, "content");
    Object root = yaml.load(content);
    if (!(root instanceof Map<?, ?> rootMap)) {
      return List.of();
    }

    Object proxiesObj = rootMap.get("proxies");
    if (!(proxiesObj instanceof List<?> proxiesList)) {
      return List.of();
    }

    List<ProxyNode> out = new ArrayList<>();
    for (Object p : proxiesList) {
      if (!(p instanceof Map<?, ?> proxy)) {
        continue;
      }
      Map<String, Object> normalized = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : proxy.entrySet()) {
        if (e.getKey() == null) {
          continue;
        }
        normalized.put(String.valueOf(e.getKey()), e.getValue());
      }
      String name = String.valueOf(normalized.getOrDefault("name", "Unnamed"));
      normalized.put("name", decorateName(upstreamName, name));
      String key = dedupKeyFromClashProxy(normalized);
      out.add(new ProxyNode((String) normalized.get("name"), normalized, key));
    }
    return out;
  }

  private static String decorateName(String upstreamName, String name) {
    String prefix = (upstreamName == null || upstreamName.isBlank()) ? "Upstream" : upstreamName.trim();
    return prefix + " | " + (name == null ? "Unnamed" : name.trim());
  }

  private static String dedupKeyFromClashProxy(Map<String, Object> proxy) {
    String type = String.valueOf(proxy.getOrDefault("type", ""));
    String server = String.valueOf(proxy.getOrDefault("server", proxy.getOrDefault("add", "")));
    String port = String.valueOf(proxy.getOrDefault("port", ""));
    String id = String.valueOf(proxy.getOrDefault("uuid", proxy.getOrDefault("password", "")));
    return (type + "|" + server + "|" + port + "|" + id).toLowerCase(Locale.ROOT);
  }
}
