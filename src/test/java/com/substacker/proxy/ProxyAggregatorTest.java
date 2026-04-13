package com.substacker.proxy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyAggregatorTest {
  private final ProxyAggregator aggregator = new ProxyAggregator();
  private final Yaml yaml = new Yaml();

  @Test
  void defaultProfileShouldKeepThreeCoreGroupsAndDeduplicateNodes() {
    String rendered = aggregator.toOpenClashYaml(
        List.of(
            node("HK-01", "dup-hk"),
            node("HK-02", "dup-hk"),
            node("JP-01", "dup-jp")
        ),
        SubscriptionConfigProfile.DEFAULT.resolve(List.of("HK-01", "HK-02", "JP-01"))
    );

    Map<String, Object> root = yaml.load(rendered);
    assertEquals("default", root.get("substacker-config"));
    assertEquals(2, ((Number) root.get("substacker-proxy-count")).intValue());
    assertEquals(List.of("MATCH,GLOBAL"), root.get("rules"));

    List<Map<String, Object>> groups = castGroups(root.get("proxy-groups"));
    assertEquals(List.of("SELECT", "AUTO", "GLOBAL"), groupNames(groups));
    assertEquals(List.of("SELECT", "AUTO", "DIRECT"), groups.get(2).get("proxies"));
  }

  @Test
  void proxiesShouldRenderAsSingleLineFlowMappings() {
    String rendered = aggregator.toOpenClashYaml(
        List.of(
            new ProxyNode(
                "HK Flow",
                Map.of(
                    "name", "HK Flow",
                    "type", "vmess",
                    "server", "example.com",
                    "port", 443,
                    "ws-opts", Map.of(
                        "path", "/images",
                        "headers", Map.of("Host", "v.qq.com")
                    )
                ),
                "hk-flow",
                null,
                null,
                null
            )
        ),
        SubscriptionConfigProfile.DEFAULT.resolve(List.of("HK Flow"))
    );

    assertTrue(rendered.contains("proxies:\n  - {"));
    assertTrue(rendered.contains("ws-opts: {path: /images, headers: {Host: v.qq.com}}"));

    Map<String, Object> root = yaml.load(rendered);
    List<Map<String, Object>> proxies = castGroups(root.get("proxies"));
    assertEquals(1, proxies.size());
    assertEquals("HK Flow", proxies.get(0).get("name"));
  }

  @Test
  void proxyGroupsShouldRenderAsSingleLineFlowMappings() {
    String rendered = aggregator.toOpenClashYaml(
        List.of(node("HK-01", "hk-01")),
        SubscriptionConfigProfile.DEFAULT.resolve(List.of("HK-01"))
    );

    assertTrue(rendered.contains("proxy-groups:\n  - {"));
    assertTrue(rendered.contains("name: SELECT"));
    assertTrue(rendered.contains("type: select"));

    Map<String, Object> root = yaml.load(rendered);
    List<Map<String, Object>> groups = castGroups(root.get("proxy-groups"));
    assertEquals(3, groups.size());
    assertEquals("SELECT", groups.get(0).get("name"));
  }

  @Test
  void fallbackProfileShouldExposeFallbackGroup() {
    String rendered = aggregator.toOpenClashYaml(
        List.of(node("TW-01", "tw-01")),
        SubscriptionConfigProfile.FALLBACK.resolve(List.of("TW-01"))
    );

    Map<String, Object> root = yaml.load(rendered);
    List<Map<String, Object>> groups = castGroups(root.get("proxy-groups"));
    assertEquals(List.of("SELECT", "AUTO", "FALLBACK", "GLOBAL"), groupNames(groups));
    assertEquals("fallback", groups.get(2).get("type"));
  }

  @Test
  void balanceProfileShouldExposeLoadBalanceGroup() {
    String rendered = aggregator.toOpenClashYaml(
        List.of(node("SG-01", "sg-01")),
        SubscriptionConfigProfile.BALANCE.resolve(List.of("SG-01"))
    );

    Map<String, Object> root = yaml.load(rendered);
    List<Map<String, Object>> groups = castGroups(root.get("proxy-groups"));
    assertEquals(List.of("SELECT", "AUTO", "BALANCE", "GLOBAL"), groupNames(groups));
    assertEquals("load-balance", groups.get(2).get("type"));
    assertEquals("round-robin", groups.get(2).get("strategy"));
  }

  private static ProxyNode node(String name, String dedupKey) {
    return new ProxyNode(
        name,
        Map.of(
            "name", name,
            "type", "trojan",
            "server", "example.com",
            "port", 443
        ),
        dedupKey,
        null,
        null,
        null
    );
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> castGroups(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private static List<String> groupNames(List<Map<String, Object>> groups) {
    return groups.stream()
        .map(group -> String.valueOf(group.get("name")))
        .toList();
  }
}
