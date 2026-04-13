package com.substacker.proxy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public enum SubscriptionConfigProfile {
  DEFAULT(
      "default",
      "默认模板",
      "保持当前输出风格，提供 SELECT、AUTO、GLOBAL 三个组。"
  ) {
    @Override
    public List<Map<String, Object>> buildProxyGroups(List<String> proxyNames) {
      return List.of(
          proxySelectGroup("SELECT", proxyNames),
          urlTestGroup("AUTO", proxyNames),
          choiceSelectGroup("GLOBAL", List.of("SELECT", "AUTO", "DIRECT"))
      );
    }
  },
  FALLBACK(
      "fallback",
      "故障转移模板",
      "增加 FALLBACK 组，适合希望在多个节点间自动切换的场景。"
  ) {
    @Override
    public List<Map<String, Object>> buildProxyGroups(List<String> proxyNames) {
      return List.of(
          proxySelectGroup("SELECT", proxyNames),
          urlTestGroup("AUTO", proxyNames),
          fallbackGroup("FALLBACK", proxyNames),
          choiceSelectGroup("GLOBAL", List.of("FALLBACK", "AUTO", "SELECT", "DIRECT"))
      );
    }
  },
  BALANCE(
      "balance",
      "负载均衡模板",
      "增加 BALANCE 组，适合把流量轮询到多个节点。"
  ) {
    @Override
    public List<Map<String, Object>> buildProxyGroups(List<String> proxyNames) {
      return List.of(
          proxySelectGroup("SELECT", proxyNames),
          urlTestGroup("AUTO", proxyNames),
          loadBalanceGroup("BALANCE", proxyNames),
          choiceSelectGroup("GLOBAL", List.of("BALANCE", "AUTO", "SELECT", "DIRECT"))
      );
    }
  };

  private static final Map<String, SubscriptionConfigProfile> BY_ID = Arrays.stream(values())
      .collect(Collectors.toUnmodifiableMap(SubscriptionConfigProfile::id, profile -> profile));

  private final String id;
  private final String label;
  private final String description;

  SubscriptionConfigProfile(String id, String label, String description) {
    this.id = id;
    this.label = label;
    this.description = description;
  }

  public abstract List<Map<String, Object>> buildProxyGroups(List<String> proxyNames);

  public List<String> buildRules() {
    return List.of("MATCH,GLOBAL");
  }

  public ResolvedSubscriptionConfig resolve(List<String> proxyNames) {
    return new ResolvedSubscriptionConfig(id, Map.of(), buildProxyGroups(proxyNames), buildRules());
  }

  public String id() {
    return id;
  }

  public String label() {
    return label;
  }

  public String description() {
    return description;
  }

  public static String defaultId() {
    return DEFAULT.id();
  }

  public static boolean exists(String id) {
    if (id == null || id.isBlank()) {
      return false;
    }
    return BY_ID.containsKey(id.trim().toLowerCase(Locale.ROOT));
  }

  public static SubscriptionConfigProfile resolveStrict(String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("config 不能为空");
    }
    SubscriptionConfigProfile profile = BY_ID.get(id.trim().toLowerCase(Locale.ROOT));
    if (profile == null) {
      throw new IllegalArgumentException("Unknown config: " + id);
    }
    return profile;
  }

  public static String normalizeIdOrDefault(String id) {
    if (id == null || id.isBlank()) {
      return defaultId();
    }
    String normalized = id.trim().toLowerCase(Locale.ROOT);
    return BY_ID.containsKey(normalized) ? normalized : defaultId();
  }

  private static Map<String, Object> proxySelectGroup(String name, List<String> proxies) {
    return selectGroup(name, mergeChoices(List.of("DIRECT", "REJECT"), proxies));
  }

  private static Map<String, Object> choiceSelectGroup(String name, List<String> choices) {
    return selectGroup(name, choices);
  }

  private static Map<String, Object> selectGroup(String name, List<String> choices) {
    Map<String, Object> g = new LinkedHashMap<>();
    g.put("name", name);
    g.put("type", "select");
    g.put("proxies", dedupe(choices));
    return g;
  }

  private static Map<String, Object> urlTestGroup(String name, List<String> proxies) {
    Map<String, Object> g = new LinkedHashMap<>();
    g.put("name", name);
    g.put("type", "url-test");
    g.put("url", "https://www.gstatic.com/generate_204");
    g.put("interval", 300);
    g.put("tolerance", 50);
    g.put("proxies", dedupe(proxies));
    return g;
  }

  private static Map<String, Object> fallbackGroup(String name, List<String> proxies) {
    Map<String, Object> g = new LinkedHashMap<>();
    g.put("name", name);
    g.put("type", "fallback");
    g.put("url", "https://www.gstatic.com/generate_204");
    g.put("interval", 300);
    g.put("proxies", dedupe(proxies));
    return g;
  }

  private static Map<String, Object> loadBalanceGroup(String name, List<String> proxies) {
    Map<String, Object> g = new LinkedHashMap<>();
    g.put("name", name);
    g.put("type", "load-balance");
    g.put("strategy", "round-robin");
    g.put("url", "https://www.gstatic.com/generate_204");
    g.put("interval", 300);
    g.put("proxies", dedupe(proxies));
    return g;
  }

  private static List<String> mergeChoices(List<String> left, List<String> right) {
    List<String> merged = new ArrayList<>(left);
    merged.addAll(right);
    return merged;
  }

  private static List<String> dedupe(List<String> values) {
    Set<String> ordered = new LinkedHashSet<>();
    for (String value : values) {
      if (value == null || value.isBlank()) {
        continue;
      }
      ordered.add(value);
    }
    return new ArrayList<>(ordered);
  }
}
