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
  private final Yaml yaml;

  public ProxyAggregator() {
    DumperOptions opts = new DumperOptions();
    opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    opts.setPrettyFlow(true);
    opts.setIndent(2);
    opts.setIndicatorIndent(1);
    opts.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
    this.yaml = new Yaml(opts);
  }

  public String toOpenClashYaml(List<ProxyNode> nodes) {
    Objects.requireNonNull(nodes, "nodes");

    List<Map<String, Object>> proxies = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    List<String> proxyNames = new ArrayList<>();

    for (ProxyNode node : nodes) {
      if (node == null || node.clashProxy() == null) {
        continue;
      }
      String key = node.dedupKey() == null ? node.name() : node.dedupKey();
      if (!seen.add(key)) {
        continue;
      }
      proxies.add(node.clashProxy());
      proxyNames.add(node.name());
    }

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("mixed-port", 7890);
    root.put("allow-lan", true);
    root.put("mode", "rule");
    root.put("log-level", "info");
    root.put("external-controller", "0.0.0.0:9090");
    root.put("secret", "");

    root.put("proxies", proxies);

    List<Map<String, Object>> groups = new ArrayList<>();
    groups.add(groupSelect("SELECT", proxyNames));
    groups.add(groupUrlTest("AUTO", proxyNames));
    groups.add(groupSelect("GLOBAL", List.of("SELECT", "AUTO", "DIRECT")));
    root.put("proxy-groups", groups);

    root.put("rules", List.of("MATCH,GLOBAL"));
    root.put("substacker-generated-at", Instant.now().toString());
    root.put("substacker-proxy-count", proxies.size());

    return yaml.dump(root);
  }

  private static Map<String, Object> groupSelect(String name, List<String> proxies) {
    Map<String, Object> g = new LinkedHashMap<>();
    g.put("name", name);
    g.put("type", "select");
    List<String> list = new ArrayList<>();
    list.add("DIRECT");
    list.add("REJECT");
    list.addAll(proxies);
    g.put("proxies", list);
    return g;
  }

  private static Map<String, Object> groupUrlTest(String name, List<String> proxies) {
    Map<String, Object> g = new LinkedHashMap<>();
    g.put("name", name);
    g.put("type", "url-test");
    g.put("url", "https://www.gstatic.com/generate_204");
    g.put("interval", 300);
    g.put("tolerance", 50);
    g.put("proxies", proxies);
    return g;
  }
}
