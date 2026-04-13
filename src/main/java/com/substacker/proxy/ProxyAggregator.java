package com.substacker.proxy;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ProxyAggregator {
  private final Yaml blockYaml;
  private final Yaml flowYaml;

  public ProxyAggregator() {
    DumperOptions blockOpts = new DumperOptions();
    blockOpts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    blockOpts.setPrettyFlow(true);
    blockOpts.setIndent(2);
    blockOpts.setIndicatorIndent(1);
    this.blockYaml = new Yaml(blockOpts);

    DumperOptions flowOpts = new DumperOptions();
    flowOpts.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
    flowOpts.setPrettyFlow(false);
    flowOpts.setIndent(2);
    flowOpts.setIndicatorIndent(1);
    flowOpts.setWidth(4096);
    this.flowYaml = new Yaml(flowOpts);
  }

  public String toOpenClashYaml(List<ProxyNode> nodes) {
    List<String> proxyNames = new ArrayList<>();
    if (nodes != null) {
      for (ProxyNode node : nodes) {
        if (node != null && node.name() != null && !node.name().isBlank()) {
          proxyNames.add(node.name());
        }
      }
    }
    return toOpenClashYaml(nodes, SubscriptionConfigProfile.DEFAULT.resolve(proxyNames));
  }

  public String toOpenClashYaml(List<ProxyNode> nodes, ResolvedSubscriptionConfig config) {
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(config, "config");

    List<Map<String, Object>> proxies = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();

    for (ProxyNode node : nodes) {
      if (node == null || node.clashProxy() == null) {
        continue;
      }
      String key = node.dedupKey() == null ? node.name() : node.dedupKey();
      if (!seen.add(key)) {
        continue;
      }
      proxies.add(node.clashProxy());
    }

    Map<String, Object> root = new LinkedHashMap<>(config.rootTemplate());
    root.remove("Proxy");
    root.remove("Proxy Group");
    root.remove("Rule");
    root.putIfAbsent("mixed-port", 7890);
    root.putIfAbsent("allow-lan", true);
    root.putIfAbsent("mode", "rule");
    root.putIfAbsent("log-level", "info");
    root.putIfAbsent("external-controller", "0.0.0.0:9090");
    root.putIfAbsent("secret", "");

    root.put("proxies", proxies);
    root.put("proxy-groups", config.proxyGroups());
    root.put("rules", config.rules());
    root.put("substacker-config", config.id());
    root.put("substacker-generated-at", Instant.now().toString());
    root.put("substacker-proxy-count", proxies.size());

    return renderRoot(root);
  }

  private String renderRoot(Map<String, Object> root) {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, Object> entry : root.entrySet()) {
      if ("proxies".equals(entry.getKey())) {
        renderFlowList(out, entry.getKey(), entry.getValue());
        continue;
      }
      if ("proxy-groups".equals(entry.getKey())) {
        renderFlowList(out, entry.getKey(), entry.getValue());
        continue;
      }
      out.append(renderEntry(entry.getKey(), entry.getValue()));
    }
    return out.toString();
  }

  @SuppressWarnings("unchecked")
  private void renderFlowList(StringBuilder out, String key, Object value) {
    out.append(key).append(":\n");
    if (!(value instanceof List<?> list)) {
      return;
    }
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> proxy)) {
        continue;
      }
      out.append("  - ");
      out.append(flowYaml.dump((Map<String, Object>) proxy).trim());
      out.append('\n');
    }
  }

  private String renderEntry(String key, Object value) {
    Map<String, Object> single = new LinkedHashMap<>();
    single.put(key, value);
    return blockYaml.dump(single);
  }
}
