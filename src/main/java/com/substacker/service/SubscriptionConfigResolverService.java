package com.substacker.service;

import com.substacker.proxy.ProxyNode;
import com.substacker.proxy.ResolvedSubscriptionConfig;
import com.substacker.proxy.SubscriptionConfigProfile;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.yaml.snakeyaml.Yaml;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class SubscriptionConfigResolverService {
  private static final Logger LOG = Logger.getLogger(SubscriptionConfigResolverService.class);
  private static final String DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204";
  private static final Pattern TEMPLATE_EXPR = Pattern.compile("\\{\\{\\s*(.+?)\\s*}}");
  private static final Set<String> RULE_SUFFIX_OPTIONS = Set.of("no-resolve", "dns-failed");
  private static final Set<String> CLASH_SUPPORTED_RULE_TYPES = Set.of(
      "DOMAIN",
      "DOMAIN-SUFFIX",
      "DOMAIN-KEYWORD",
      "DOMAIN-REGEX",
      "GEOSITE",
      "GEOIP",
      "IP-CIDR",
      "IP-CIDR6",
      "IP-ASN",
      "SRC-IP-CIDR",
      "SRC-IP-ASN",
      "SRC-PORT",
      "DST-PORT",
      "IN-PORT",
      "PROCESS-NAME",
      "PROCESS-PATH",
      "NETWORK",
      "RULE-SET",
      "MATCH"
  );
  private static final Map<String, String> RULE_TYPE_ALIASES = Map.ofEntries(
      Map.entry("HOST", "DOMAIN"),
      Map.entry("HOST-SUFFIX", "DOMAIN-SUFFIX"),
      Map.entry("HOST-KEYWORD", "DOMAIN-KEYWORD"),
      Map.entry("DEST-PORT", "DST-PORT"),
      Map.entry("FINAL", "MATCH")
  );
  private static final Set<String> REMOTE_RULE_PREFIXES = Set.of(
      "domain",
      "surge",
      "quanx",
      "classical",
      "classic",
      "ipcidr",
      "clash-domain",
      "clash-classic",
      "clash-classical",
      "clash-ipcidr"
  );
  private static final Set<String> TEST_GROUP_TYPES = Set.of("url-test", "fallback", "load-balance");
  private static final Set<String> REGIONAL_GROUP_KEYWORDS = Set.of(
      "香港", "台湾", "日本", "新加坡", "美国", "韩国", "英国", "德国", "法国", "荷兰", "加拿大", "澳大利亚",
      "印度", "马来西亚", "菲律宾", "泰国", "越南", "土耳其", "阿联酋", "巴西", "西班牙", "瑞士", "俄罗",
      "港", "台", "日", "新", "美", "韩", "英", "德", "法", "荷", "加", "澳", "印", "马", "菲", "泰", "越"
  );
  private static final Set<String> REGIONAL_GROUP_SUFFIX_HINTS = Set.of("节点", "专线", "线路", "专区", "地区");
  private static final Set<String> REGIONAL_GROUP_EXCLUDES = Set.of(
      "选择", "切换", "手动", "自动", "故转", "全球", "全部", "其他", "高速", "福利", "家宽", "媒体", "漏网", "直连"
  );
  private static final Map<String, List<String>> REGIONAL_NODE_ALIASES = Map.ofEntries(
      Map.entry("香港", List.of("香港", "hong kong", "hongkong", "hk", "hkg")),
      Map.entry("台湾", List.of("台湾", "taiwan", "tw", "tpe")),
      Map.entry("日本", List.of("日本", "japan", "jp", "tokyo", "osaka")),
      Map.entry("新加坡", List.of("新加坡", "singapore", "sg")),
      Map.entry("美国", List.of("美国", "united states", "usa", "us ", "america", "los angeles", "san jose", "silicon valley")),
      Map.entry("韩国", List.of("韩国", "korea", "kr", "seoul")),
      Map.entry("英国", List.of("英国", "united kingdom", "uk", "britain", "london")),
      Map.entry("德国", List.of("德国", "germany", "de", "frankfurt")),
      Map.entry("法国", List.of("法国", "france", "fr", "paris")),
      Map.entry("荷兰", List.of("荷兰", "netherlands", "nl", "amsterdam")),
      Map.entry("加拿大", List.of("加拿大", "canada", "ca", "toronto", "vancouver")),
      Map.entry("澳大利亚", List.of("澳大利亚", "australia", "au", "sydney", "melbourne")),
      Map.entry("印度", List.of("印度", "india", "in", "mumbai")),
      Map.entry("马来西亚", List.of("马来西亚", "malaysia", "my", "kuala lumpur")),
      Map.entry("菲律宾", List.of("菲律宾", "philippines", "ph", "manila")),
      Map.entry("泰国", List.of("泰国", "thailand", "th", "bangkok")),
      Map.entry("越南", List.of("越南", "vietnam", "vn", "ho chi minh", "hanoi"))
  );

  private final SubscriptionSettingsService subscriptionSettingsService;
  private final HttpFetchService httpFetchService;
  private final Yaml yaml;

  public SubscriptionConfigResolverService(SubscriptionSettingsService subscriptionSettingsService, HttpFetchService httpFetchService) {
    this.subscriptionSettingsService = subscriptionSettingsService;
    this.httpFetchService = httpFetchService;
    this.yaml = new Yaml();
  }

  public ResolvedSubscriptionConfig resolve(String requestedConfigId, List<ProxyNode> nodes) {
    List<ProxyNode> availableNodes = dedupeNodes(nodes);
    List<String> proxyNames = availableNodes.stream()
        .map(ProxyNode::name)
        .filter(name -> name != null && !name.isBlank())
        .toList();

    String effective = requestedConfigId == null || requestedConfigId.isBlank()
        ? subscriptionSettingsService.current().defaultConfigId()
        : requestedConfigId.trim();

    if (SubscriptionConfigProfile.exists(effective)) {
      return SubscriptionConfigProfile.resolveStrict(effective).resolve(proxyNames);
    }

    ConcurrentHashMap<String, SourceRef> sourceCache = new ConcurrentHashMap<>();
    SourceRef mainSource = loadMainSource(effective, sourceCache);
    ParseState state = new ParseState();
    collectEntries(mainSource, state, new LinkedHashSet<>(), null, sourceCache);

    Map<String, Object> baseRoot = loadBaseRoot(state.clashRuleBase, state.clashRuleBaseOwner, state.localValues, sourceCache);
    List<Map<String, Object>> originalGroups = extractGroupList(baseRoot);
    List<String> originalRules = extractRuleList(baseRoot);

    List<Map<String, Object>> groups = buildGroups(state.groups, availableNodes);
    if (groups.isEmpty()) {
      groups = !originalGroups.isEmpty()
          ? originalGroups
          : SubscriptionConfigProfile.DEFAULT.buildProxyGroups(proxyNames);
    }
    groups = maybeConvertRegionalGroupsToUrlTest(groups);

    List<String> generatedRules = state.enableRuleGenerator ? buildRules(state.rulesets, sourceCache) : List.of();
    List<String> rules;
    if (generatedRules.isEmpty()) {
      rules = !originalRules.isEmpty() ? originalRules : SubscriptionConfigProfile.DEFAULT.buildRules();
    } else if (state.overwriteOriginalRules || originalRules.isEmpty()) {
      rules = generatedRules;
    } else {
      rules = mergeRules(originalRules, generatedRules);
    }

    return new ResolvedSubscriptionConfig(effective, baseRoot, groups, rules);
  }

  private List<Map<String, Object>> maybeConvertRegionalGroupsToUrlTest(List<Map<String, Object>> groups) {
    if (subscriptionSettingsService == null || !subscriptionSettingsService.current().regionalGroupsUrlTestEnabled()) {
      return groups;
    }

    List<Map<String, Object>> rewritten = new ArrayList<>(groups.size());
    for (Map<String, Object> group : groups) {
      rewritten.add(convertRegionalGroupToUrlTest(group));
    }
    return rewritten;
  }

  private Map<String, Object> convertRegionalGroupToUrlTest(Map<String, Object> group) {
    if (group == null) {
      return Map.of();
    }
    if (!looksLikeRegionalSelectGroup(group)) {
      return group;
    }

    Map<String, Object> rewritten = new LinkedHashMap<>(group);
    rewritten.put("type", "url-test");
    rewritten.putIfAbsent("url", DEFAULT_TEST_URL);
    rewritten.putIfAbsent("interval", 300);
    rewritten.putIfAbsent("tolerance", 50);
    return rewritten;
  }

  private boolean looksLikeRegionalSelectGroup(Map<String, Object> group) {
    String type = String.valueOf(group.getOrDefault("type", "")).trim().toLowerCase(Locale.ROOT);
    if (!"select".equals(type)) {
      return false;
    }

    String name = String.valueOf(group.getOrDefault("name", "")).trim();
    if (name.isBlank()) {
      return false;
    }
    for (String exclude : REGIONAL_GROUP_EXCLUDES) {
      if (name.contains(exclude)) {
        return false;
      }
    }

    if (detectRegionKey(name) == null) {
      return false;
    }

    for (String suffix : REGIONAL_GROUP_SUFFIX_HINTS) {
      if (name.contains(suffix)) {
        return true;
      }
    }
    return false;
  }

  private SourceRef loadMainSource(String effectiveConfig, Map<String, SourceRef> cache) {
    if (looksLikeHttpUrl(effectiveConfig)) {
      return loadSource(effectiveConfig, null, cache);
    }
    String templateUrl = subscriptionSettingsService == null ? null : subscriptionSettingsService.findTemplateUrl(effectiveConfig);
    if (templateUrl != null) {
      return loadSource(templateUrl, null, cache);
    }
    return loadSource(effectiveConfig, null, cache);
  }

  private List<Map<String, Object>> buildGroups(List<GroupSpec> specs, List<ProxyNode> availableNodes) {
    List<Map<String, Object>> groups = new ArrayList<>();
    for (GroupSpec spec : specs) {
      Map<String, Object> group = buildGroup(spec, availableNodes);
      if (group != null) {
        groups.add(group);
      }
    }
    return groups;
  }

  private Map<String, Object> buildGroup(GroupSpec spec, List<ProxyNode> availableNodes) {
    String type = spec.type().toLowerCase(Locale.ROOT);
    List<String> proxies = resolveSelectors(spec.selectors(), availableNodes);
    proxies = mergeRegionalAliasMatches(spec.name(), proxies, availableNodes);
    List<String> fallbackProxyNames = availableNodes.stream()
        .map(ProxyNode::name)
        .filter(name -> name != null && !name.isBlank())
        .toList();

    if ("ssid".equals(type)) {
      LOG.warnf("Ignore unsupported proxy group type `ssid`: %s", spec.name());
      return null;
    }

    Map<String, Object> group = baseGroup(spec.name(), type);
    if (TEST_GROUP_TYPES.contains(type)) {
      group.put("url", spec.testUrl() == null || spec.testUrl().isBlank() ? DEFAULT_TEST_URL : spec.testUrl());
      group.put("interval", spec.interval() == null ? 300 : spec.interval());
      if (spec.timeout() != null) {
        group.put("timeout", spec.timeout());
      }
      if (spec.tolerance() != null && "url-test".equals(type)) {
        group.put("tolerance", spec.tolerance());
      }
      if ("load-balance".equals(type) && spec.strategy() != null && !spec.strategy().isBlank()) {
        group.put("strategy", spec.strategy());
      }
    }

    if ("select".equals(type) || "relay".equals(type) || TEST_GROUP_TYPES.contains(type)) {
      group.put("proxies", proxies.isEmpty() ? fallbackProxyNames : proxies);
      return group;
    }

    // For unknown Clash-compatible group types, keep the type and fall back to a plain choices list.
    group.put("proxies", proxies.isEmpty() ? fallbackProxyNames : proxies);
    return group;
  }

  private List<String> mergeRegionalAliasMatches(String groupName, List<String> currentMatches, List<ProxyNode> availableNodes) {
    String regionKey = detectRegionKey(groupName);
    if (regionKey == null) {
      return currentMatches;
    }

    List<String> aliases = REGIONAL_NODE_ALIASES.get(regionKey);
    if (aliases == null || aliases.isEmpty()) {
      return currentMatches;
    }

    LinkedHashSet<String> merged = new LinkedHashSet<>(currentMatches);
    for (ProxyNode node : availableNodes) {
      String proxyName = node.name();
      if (proxyName == null || proxyName.isBlank()) {
        continue;
      }
      String normalized = " " + proxyName.toLowerCase(Locale.ROOT) + " ";
      for (String alias : aliases) {
        if (normalized.contains(alias.toLowerCase(Locale.ROOT))) {
          merged.add(proxyName);
          break;
        }
      }
    }
    return new ArrayList<>(merged);
  }

  private String detectRegionKey(String groupName) {
    if (groupName == null || groupName.isBlank()) {
      return null;
    }
    String name = groupName.trim();
    for (String exclude : REGIONAL_GROUP_EXCLUDES) {
      if (name.contains(exclude)) {
        return null;
      }
    }
    for (String regionKey : REGIONAL_NODE_ALIASES.keySet()) {
      if (name.contains(regionKey)) {
        return regionKey;
      }
    }
    for (String keyword : REGIONAL_GROUP_KEYWORDS) {
      if (name.contains(keyword)) {
        return keyword;
      }
    }
    return null;
  }

  private static Map<String, Object> baseGroup(String name, String type) {
    Map<String, Object> group = new LinkedHashMap<>();
    group.put("name", name);
    group.put("type", type);
    return group;
  }

  private List<String> resolveSelectors(List<String> selectors, List<ProxyNode> availableNodes) {
    LinkedHashSet<String> resolved = new LinkedHashSet<>();
    List<String> allProxyNames = availableNodes.stream()
        .map(ProxyNode::name)
        .filter(name -> name != null && !name.isBlank())
        .toList();

    for (String selector : selectors) {
      if (selector == null || selector.isBlank()) {
        continue;
      }
      String trimmed = selector.trim();
      if (trimmed.equalsIgnoreCase("[]ALL")) {
        resolved.addAll(allProxyNames);
        continue;
      }
      if (trimmed.startsWith("[]")) {
        String inner = normalizeSelectorPattern(trimmed.substring(2));
        if (inner.equalsIgnoreCase("ALL")) {
          resolved.addAll(allProxyNames);
        } else if (!inner.isBlank()) {
          int before = resolved.size();
          addRegexMatches(inner, availableNodes, resolved);
          if (resolved.size() == before) {
            resolved.add(inner);
          }
        }
        continue;
      }
      if (trimmed.startsWith("script:")) {
        LOG.warnf("Ignore unsupported selector script: %s", trimmed);
        continue;
      }

      List<String> specialMatches = resolveSpecialSelector(trimmed, availableNodes);
      if (specialMatches != null) {
        resolved.addAll(specialMatches);
        continue;
      }

      addRegexMatches(trimmed, availableNodes, resolved);
    }
    return new ArrayList<>(resolved);
  }

  private List<String> resolveSpecialSelector(String selector, List<ProxyNode> availableNodes) {
    if (!selector.startsWith("!!")) {
      return null;
    }
    if (selector.startsWith("!!INSERT=")) {
      LOG.warnf("Ignore unsupported selector: %s", selector);
      return List.of();
    }

    String body = selector.substring(2);
    String[] parts = body.split("!!");
    List<ProxyNode> current = new ArrayList<>(availableNodes);
    boolean handled = false;

    for (String rawPart : parts) {
      String part = rawPart == null ? "" : rawPart.trim();
      if (part.isBlank()) {
        continue;
      }
      if (part.startsWith("GROUPID=")) {
        current = filterByGroupId(current, part.substring("GROUPID=".length()));
        handled = true;
        continue;
      }
      if (part.startsWith("GROUP=")) {
        current = filterBySourceName(current, part.substring("GROUP=".length()), true);
        handled = true;
        continue;
      }
      if (part.startsWith("PROVIDER=")) {
        current = filterByProviderName(current, part.substring("PROVIDER=".length()));
        handled = true;
        continue;
      }
      if (part.startsWith("INSERT=")) {
        LOG.warnf("Ignore unsupported selector: !!%s", part);
        return List.of();
      }
      current = filterByRegex(current, part);
      handled = true;
    }

    if (!handled) {
      return null;
    }
    return current.stream()
        .map(ProxyNode::name)
        .filter(name -> name != null && !name.isBlank())
        .toList();
  }

  private List<ProxyNode> filterByGroupId(List<ProxyNode> nodes, String value) {
    Set<Integer> accepted = parseIntSet(value);
    boolean negate = isNegated(value);
    return nodes.stream()
        .filter(node -> {
          Integer groupId = node.sourceGroupId();
          boolean match = groupId != null && accepted.contains(groupId);
          return negate ? !match : match;
        })
        .toList();
  }

  private List<ProxyNode> filterBySourceName(List<ProxyNode> nodes, String value, boolean useGroupName) {
    Set<String> accepted = parseStringSet(value);
    boolean negate = isNegated(value);
    return nodes.stream()
        .filter(node -> {
          String actual = useGroupName ? node.sourceGroupName() : node.sourceProviderName();
          boolean match = actual != null && accepted.contains(actual.trim().toLowerCase(Locale.ROOT));
          return negate ? !match : match;
        })
        .toList();
  }

  private List<ProxyNode> filterByProviderName(List<ProxyNode> nodes, String value) {
    return filterBySourceName(nodes, value, false);
  }

  private List<ProxyNode> filterByRegex(List<ProxyNode> nodes, String patternText) {
    String normalized = normalizeSelectorPattern(patternText);
    try {
      Pattern pattern = Pattern.compile(normalized);
      return nodes.stream()
          .filter(node -> node.name() != null && pattern.matcher(node.name()).find())
          .toList();
    } catch (PatternSyntaxException e) {
      LOG.warnf("Ignore invalid regex selector: %s", normalized);
      return List.of();
    }
  }

  private void addRegexMatches(String patternText, List<ProxyNode> availableNodes, LinkedHashSet<String> out) {
    String normalized = normalizeSelectorPattern(patternText);
    try {
      Pattern pattern = Pattern.compile(normalized);
      for (ProxyNode node : availableNodes) {
        if (node.name() != null && pattern.matcher(node.name()).find()) {
          out.add(node.name());
        }
      }
    } catch (PatternSyntaxException e) {
      LOG.warnf("Ignore invalid regex selector: %s", normalized);
    }
  }

  private static String normalizeSelectorPattern(String patternText) {
    String normalized = patternText == null ? "" : patternText.trim();
    if (isQuoted(normalized)) {
      normalized = unquote(normalized).trim();
    }
    return normalized;
  }

  private static boolean looksLikeRegexSelector(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    return value.startsWith("(?")
        || value.contains(".*")
        || value.contains(".+")
        || value.contains("|")
        || value.contains("\\d")
        || value.contains("\\w")
        || value.contains("\\s");
  }

  private List<String> buildRules(List<RuleSetRef> rulesets, Map<String, SourceRef> cache) {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      record Task(RuleSetRef ruleset, Future<List<String>> future) {}
      List<Task> tasks = new ArrayList<>();
      for (RuleSetRef ruleset : rulesets) {
        tasks.add(new Task(ruleset, executor.submit(() -> expandRuleSet(ruleset, cache))));
      }
      List<String> rules = new ArrayList<>();
      for (Task t : tasks) {
        try {
          rules.addAll(t.future().get());
        } catch (Exception e) {
          Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
          LOG.warnf(
              "Ignore failed remote ruleset: policy=%s source=%s reason=%s",
              t.ruleset().policy(),
              t.ruleset().source(),
              rootCauseMessage(cause)
          );
        }
      }
      return dedupeStrings(rules);
    }
  }

  private List<String> expandRuleSet(RuleSetRef ruleset, Map<String, SourceRef> cache) {
    String source = ruleset.source();
    if (source.startsWith("[]")) {
      return List.of(buildInlineRule(source.substring(2), ruleset.policy()));
    }

    String mode = "";
    String remoteSource = source;
    int prefixSplit = source.indexOf(':');
    if (prefixSplit > 0) {
      String prefix = source.substring(0, prefixSplit).trim().toLowerCase(Locale.ROOT);
      if (REMOTE_RULE_PREFIXES.contains(prefix)) {
        mode = canonicalRuleMode(prefix);
        remoteSource = source.substring(prefixSplit + 1).trim();
      }
    }

    SourceRef ruleSource = loadSource(remoteSource, ruleset.owner(), cache);
    return parseRuleListBody(ruleSource.body(), mode, ruleset.policy());
  }

  private List<String> parseRuleListBody(String body, String mode, String policy) {
    Object yamlRoot = tryLoadYaml(body);
    if (yamlRoot instanceof Map<?, ?> map) {
      Object payload = map.get("payload");
      if (payload instanceof List<?> items) {
        List<String> rules = new ArrayList<>();
        for (Object item : items) {
          if (item == null) {
            continue;
          }
          String line = String.valueOf(item).trim();
          if (!line.isBlank()) {
            String rule = toPolicyRule(line, mode, policy);
            if (!rule.isBlank()) {
              rules.add(rule);
            }
          }
        }
        return rules;
      }
    }

    List<String> rules = new ArrayList<>();
    for (String rawLine : body.split("\\R")) {
      String line = sanitizeLine(rawLine);
      if (line.isBlank() || line.equalsIgnoreCase("payload:")) {
        continue;
      }
      if (line.startsWith("- ")) {
        line = line.substring(2).trim();
      }
      String rule = toPolicyRule(line, mode, policy);
      if (!rule.isBlank()) {
        rules.add(rule);
      }
    }
    return rules;
  }

  private String toPolicyRule(String rawLine, String mode, String policy) {
    if (rawLine == null || rawLine.isBlank()) {
      return "";
    }
    String trimmed = rawLine.trim();
    String standaloneRuleType = null;
    if (!trimmed.contains(",")) {
      standaloneRuleType = normalizeRuleType(trimmed);
    }
    if (standaloneRuleType != null) {
      if (standaloneRuleType.equals("MATCH")) {
        return "MATCH," + policy;
      }
    }

    String normalizedMode = mode == null ? "" : mode.toLowerCase(Locale.ROOT);
    if ("domain".equals(normalizedMode) || "clash-domain".equals(normalizedMode)) {
      if (trimmed.contains(",")) {
        return attachPolicy(trimmed, policy);
      }
      String value = trimmed.replaceFirst("^\\+?\\.", "").trim();
      return "DOMAIN-SUFFIX," + value + "," + policy;
    }
    if ("ipcidr".equals(normalizedMode) || "clash-ipcidr".equals(normalizedMode)) {
      if (trimmed.contains(",")) {
        return attachPolicy(trimmed, policy);
      }
      return "IP-CIDR," + trimmed + "," + policy;
    }
    if (!trimmed.contains(",")) {
      return "DOMAIN-SUFFIX," + trimmed + "," + policy;
    }
    return attachPolicy(trimmed, policy);
  }

  private String buildInlineRule(String inlineSource, String policy) {
    List<String> parts = splitComma(inlineSource);
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("Invalid inline ruleset: " + inlineSource);
    }

    String ruleType = normalizeRuleType(parts.get(0));
    if (ruleType == null) {
      return "";
    }
    if (ruleType.equals("MATCH")) {
      return "MATCH," + policy;
    }

    List<String> remainder = new ArrayList<>(parts.subList(1, parts.size()));
    List<String> suffixOptions = popSuffixOptions(remainder);

    List<String> out = new ArrayList<>();
    out.add(ruleType);
    out.addAll(remainder);
    out.add(policy);
    out.addAll(suffixOptions);
    return String.join(",", out);
  }

  private String attachPolicy(String rawRule, String policy) {
    List<String> parts = splitComma(rawRule);
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("Invalid rule line: " + rawRule);
    }
    String ruleType = normalizeRuleType(parts.get(0));
    if (ruleType == null) {
      return "";
    }
    if (ruleType.equals("MATCH")) {
      return "MATCH," + policy;
    }

    List<String> remainder = new ArrayList<>(parts.subList(1, parts.size()));
    List<String> suffixOptions = popSuffixOptions(remainder);

    List<String> out = new ArrayList<>();
    out.add(ruleType);
    out.addAll(remainder);
    out.add(policy);
    out.addAll(suffixOptions);
    return String.join(",", out);
  }

  private String normalizeRuleType(String rawRuleType) {
    if (rawRuleType == null || rawRuleType.isBlank()) {
      return null;
    }
    String normalized = rawRuleType.trim().toUpperCase(Locale.ROOT);
    normalized = RULE_TYPE_ALIASES.getOrDefault(normalized, normalized);
    if (CLASH_SUPPORTED_RULE_TYPES.contains(normalized)) {
      return normalized;
    }
    LOG.warnf("Ignore unsupported clash rule type: %s", rawRuleType);
    return null;
  }

  private static List<String> popSuffixOptions(List<String> remainder) {
    List<String> suffixOptions = new ArrayList<>();
    while (!remainder.isEmpty()) {
      String last = remainder.get(remainder.size() - 1).trim().toLowerCase(Locale.ROOT);
      if (!RULE_SUFFIX_OPTIONS.contains(last)) {
        break;
      }
      suffixOptions.add(0, remainder.remove(remainder.size() - 1).trim());
    }
    return suffixOptions;
  }

  private void collectEntries(SourceRef source, ParseState state, Set<String> visited, ImportKind forcedKind, Map<String, SourceRef> cache) {
    if (!visited.add(source.identity())) {
      LOG.warnf("Ignore recursive import: %s", source.identity());
      return;
    }
    try {
      preScanTemplateLocals(source.body(), state.localValues);
      List<String> renderedLines = renderTemplateLines(source, state.localValues, new LinkedHashSet<>(), cache);
      boolean hasSection = renderedLines.stream()
          .map(SubscriptionConfigResolverService::sanitizeLine)
          .anyMatch(line -> line.startsWith("[") && line.endsWith("]"));
      String currentSection = forcedKind != null && !hasSection ? "custom" : "";

      for (String rawLine : renderedLines) {
        String line = sanitizeLine(rawLine);
        if (line.isBlank()) {
          continue;
        }
        if (line.startsWith("[") && line.endsWith("]")) {
          currentSection = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
          continue;
        }
        if ("template".equals(currentSection)) {
          parseTemplateLocal(line, state);
          continue;
        }
        if (!"custom".equals(currentSection)) {
          continue;
        }
        processCustomLine(source, line, state, visited, forcedKind, cache);
      }
    } finally {
      visited.remove(source.identity());
    }
  }

  private void preScanTemplateLocals(String body, Map<String, String> localValues) {
    String currentSection = "";
    for (String rawLine : body.split("\\R")) {
      String line = sanitizeLine(rawLine);
      if (line.isBlank()) {
        continue;
      }
      if (line.startsWith("[") && line.endsWith("]")) {
        currentSection = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
        continue;
      }
      if (!"template".equals(currentSection)) {
        continue;
      }
      int eq = line.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(eq + 1).trim();
      if (!key.isBlank()) {
        localValues.putIfAbsent(key, value);
      }
    }
  }

  private void processCustomLine(
      SourceRef source,
      String line,
      ParseState state,
      Set<String> visited,
      ImportKind forcedKind,
      Map<String, SourceRef> cache
  ) {
    String key = null;
    String value = line;
    int eq = line.indexOf('=');
    if (eq >= 0) {
      key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      value = line.substring(eq + 1).trim();
    }

    if ((key == null || key.isBlank()) && forcedKind != null) {
      if (forcedKind == ImportKind.RULESET) {
        RuleSetRef ref = parseRuleSet(value, source);
        if (ref != null) {
          state.rulesets.add(ref);
        }
      } else if (forcedKind == ImportKind.GROUP) {
        GroupSpec spec = parseGroup(value);
        if (spec != null) {
          state.groups.add(spec);
        }
      }
      return;
    }

    if (key == null || key.isBlank()) {
      return;
    }

    if ("ruleset".equals(key)) {
      if (value.startsWith("!!import:")) {
        collectEntries(loadSource(value.substring("!!import:".length()), source, cache), state, visited, ImportKind.RULESET, cache);
        return;
      }
      RuleSetRef ref = parseRuleSet(value, source);
      if (ref != null) {
        state.rulesets.add(ref);
      }
      return;
    }

    if ("custom_proxy_group".equals(key) || "proxy_group".equals(key)) {
      if (value.startsWith("!!import:")) {
        collectEntries(loadSource(value.substring("!!import:".length()), source, cache), state, visited, ImportKind.GROUP, cache);
        return;
      }
      GroupSpec spec = parseGroup(value);
      if (spec != null) {
        state.groups.add(spec);
      }
      return;
    }

    state.localValues.put(key, value);
    if ("enable_rule_generator".equals(key)) {
      state.enableRuleGenerator = truthy(value);
      return;
    }
    if ("overwrite_original_rules".equals(key)) {
      state.overwriteOriginalRules = truthy(value);
      return;
    }
    if ("clash_rule_base".equals(key) || "rule_base".equals(key)) {
      state.clashRuleBase = value;
      state.clashRuleBaseOwner = source;
    }
  }

  private void parseTemplateLocal(String line, ParseState state) {
    int eq = line.indexOf('=');
    if (eq <= 0) {
      return;
    }
    String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
    String value = line.substring(eq + 1).trim();
    if (!key.isBlank()) {
      state.localValues.put(key, value);
    }
  }

  private RuleSetRef parseRuleSet(String value, SourceRef owner) {
    int firstComma = value.indexOf(',');
    if (firstComma <= 0 || firstComma >= value.length() - 1) {
      return null;
    }

    String policy = value.substring(0, firstComma).trim();
    String sourcePart = value.substring(firstComma + 1).trim();
    if (policy.isBlank() || sourcePart.isBlank()) {
      return null;
    }

    Integer interval = null;
    if (!sourcePart.startsWith("[]")) {
      int lastComma = sourcePart.lastIndexOf(',');
      if (lastComma > 0 && lastComma < sourcePart.length() - 1) {
        String maybeInterval = sourcePart.substring(lastComma + 1).trim();
        if (looksLikeInteger(maybeInterval)) {
          interval = Integer.parseInt(maybeInterval);
          sourcePart = sourcePart.substring(0, lastComma).trim();
        }
      }
    }

    return new RuleSetRef(policy, sourcePart, interval, owner);
  }

  private GroupSpec parseGroup(String value) {
    String[] parts = value.split("`", -1);
    if (parts.length < 2) {
      return null;
    }

    String name = parts[0].trim();
    String type = parts[1].trim();
    if (name.isBlank() || type.isBlank()) {
      return null;
    }

    List<String> tail = new ArrayList<>();
    for (int i = 2; i < parts.length; i++) {
      tail.add(parts[i] == null ? "" : parts[i].trim());
    }

    List<String> selectors = new ArrayList<>(tail);
    String testUrl = null;
    Integer interval = null;
    Integer timeout = null;
    Integer tolerance = null;
    String strategy = null;

    String normalizedType = type.toLowerCase(Locale.ROOT);
    if (TEST_GROUP_TYPES.contains(normalizedType) && tail.size() >= 2) {
      selectors = new ArrayList<>(tail.subList(0, tail.size() - 2));
      testUrl = blankToNull(tail.get(tail.size() - 2));

      List<String> timing = splitComma(tail.get(tail.size() - 1));
      interval = !timing.isEmpty() ? parseInteger(timing.get(0), 300) : 300;
      timeout = timing.size() > 1 ? parseInteger(timing.get(1), null) : null;
      tolerance = timing.size() > 2 ? parseInteger(timing.get(2), null) : null;

      if ("load-balance".equals(normalizedType) && selectors.size() > 1) {
        String maybeStrategy = selectors.get(selectors.size() - 1).trim().toLowerCase(Locale.ROOT);
        if (maybeStrategy.equals("round-robin")
            || maybeStrategy.equals("consistent-hashing")
            || maybeStrategy.equals("sticky-sessions")) {
          strategy = maybeStrategy;
          selectors = new ArrayList<>(selectors.subList(0, selectors.size() - 1));
        }
      }
    }

    return new GroupSpec(name, normalizedType, selectors, testUrl, interval, timeout, tolerance, strategy);
  }

  private List<String> renderTemplateLines(SourceRef source, Map<String, String> localValues, Set<String> includeStack, Map<String, SourceRef> cache) {
    if (!includeStack.add(source.identity())) {
      LOG.warnf("Ignore recursive template include: %s", source.identity());
      return List.of();
    }
    try {
      ArrayDeque<ConditionalFrame> frames = new ArrayDeque<>();
      List<String> out = new ArrayList<>();
      for (String rawLine : source.body().split("\\R", -1)) {
        String noBom = rawLine.replace("\uFEFF", "");
        String trimmed = noBom.trim();
        if (trimmed.startsWith("{%") && trimmed.endsWith("%}")) {
          String directive = trimmed.substring(2, trimmed.length() - 2).trim();
          if (directive.startsWith("include ")) {
            if (isFrameActive(frames)) {
              String includeRef = extractQuotedValue(directive.substring("include".length()).trim());
              if (includeRef != null && !includeRef.isBlank()) {
                out.addAll(renderTemplateLines(loadSource(includeRef, source, cache), localValues, includeStack, cache));
              }
            }
            continue;
          }
          handleDirective(directive, frames, localValues);
          continue;
        }
        if (isFrameActive(frames)) {
          out.add(interpolateTemplateExpressions(noBom, localValues));
        }
      }
      return out;
    } finally {
      includeStack.remove(source.identity());
    }
  }

  private void handleDirective(String directive, ArrayDeque<ConditionalFrame> frames, Map<String, String> localValues) {
    String lowered = directive.toLowerCase(Locale.ROOT);
    if (lowered.startsWith("if ")) {
      boolean parentActive = isFrameActive(frames);
      boolean matched = evaluateCondition(directive.substring(3), localValues);
      frames.push(new ConditionalFrame(parentActive, parentActive && matched, matched));
      return;
    }
    if (lowered.startsWith("elif ")) {
      if (frames.isEmpty()) {
        return;
      }
      ConditionalFrame current = frames.peek();
      if (current == null) {
        return;
      }
      if (current.parentActive() && !current.anyMatched() && evaluateCondition(directive.substring(5), localValues)) {
        frames.pop();
        frames.push(new ConditionalFrame(current.parentActive(), true, true));
      } else {
        frames.pop();
        frames.push(new ConditionalFrame(current.parentActive(), false, current.anyMatched()));
      }
      return;
    }
    if (lowered.equals("else")) {
      if (frames.isEmpty()) {
        return;
      }
      ConditionalFrame current = frames.pop();
      boolean active = current.parentActive() && !current.anyMatched();
      frames.push(new ConditionalFrame(current.parentActive(), active, true));
      return;
    }
    if (lowered.equals("endif")) {
      if (!frames.isEmpty()) {
        frames.pop();
      }
    }
  }

  private static boolean isFrameActive(ArrayDeque<ConditionalFrame> frames) {
    for (ConditionalFrame frame : frames) {
      if (!frame.active()) {
        return false;
      }
    }
    return true;
  }

  private boolean evaluateCondition(String expression, Map<String, String> localValues) {
    String expr = stripOuterParens(expression == null ? "" : expression.trim());
    if (expr.isBlank()) {
      return false;
    }

    List<String> orParts = splitTopLevelByWord(expr, "or");
    if (orParts.size() > 1) {
      for (String part : orParts) {
        if (evaluateCondition(part, localValues)) {
          return true;
        }
      }
      return false;
    }

    List<String> andParts = splitTopLevelByWord(expr, "and");
    if (andParts.size() > 1) {
      for (String part : andParts) {
        if (!evaluateCondition(part, localValues)) {
          return false;
        }
      }
      return true;
    }

    if (expr.toLowerCase(Locale.ROOT).startsWith("not ")) {
      return !evaluateCondition(expr.substring(4), localValues);
    }

    Comparison comparison = findComparison(expr);
    if (comparison != null) {
      String left = stringifyValue(evaluateValue(comparison.left(), localValues));
      String right = stringifyValue(evaluateValue(comparison.right(), localValues));
      boolean equals = Objects.equals(left, right);
      return comparison.negated() ? !equals : equals;
    }

    return truthyObject(evaluateValue(expr, localValues));
  }

  private Object evaluateValue(String expression, Map<String, String> localValues) {
    String expr = stripOuterParens(expression == null ? "" : expression.trim());
    if (expr.isBlank()) {
      return null;
    }
    if (isQuoted(expr)) {
      return unquote(expr);
    }
    if (expr.equalsIgnoreCase("true")) {
      return Boolean.TRUE;
    }
    if (expr.equalsIgnoreCase("false")) {
      return Boolean.FALSE;
    }
    if (expr.toLowerCase(Locale.ROOT).startsWith("default(") && expr.endsWith(")")) {
      List<String> args = splitArguments(expr.substring("default(".length(), expr.length() - 1));
      if (args.isEmpty()) {
        return null;
      }
      Object first = resolveMaybePathValue(evaluateValue(args.get(0), localValues), localValues);
      if (!isBlankValue(first)) {
        return first;
      }
      return args.size() > 1 ? evaluateValue(args.get(1), localValues) : null;
    }
    if (expr.toLowerCase(Locale.ROOT).startsWith("exists(") && expr.endsWith(")")) {
      List<String> args = splitArguments(expr.substring("exists(".length(), expr.length() - 1));
      if (args.isEmpty()) {
        return Boolean.FALSE;
      }
      Object value = resolveMaybePathValue(evaluateValue(args.get(0), localValues), localValues);
      return !isBlankValue(value);
    }
    if (expr.toLowerCase(Locale.ROOT).startsWith("bool(") && expr.endsWith(")")) {
      List<String> args = splitArguments(expr.substring("bool(".length(), expr.length() - 1));
      if (args.isEmpty()) {
        return Boolean.FALSE;
      }
      Object value = resolveMaybePathValue(evaluateValue(args.get(0), localValues), localValues);
      return truthyObject(value);
    }
    if (expr.startsWith("local.")) {
      return localValues.get(expr.substring("local.".length()).toLowerCase(Locale.ROOT));
    }
    if (expr.startsWith("request.") || expr.startsWith("global.")) {
      return null;
    }
    return expr;
  }

  private String interpolateTemplateExpressions(String line, Map<String, String> localValues) {
    Matcher matcher = TEMPLATE_EXPR.matcher(line);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      Object value = resolveMaybePathValue(evaluateValue(matcher.group(1), localValues), localValues);
      matcher.appendReplacement(out, Matcher.quoteReplacement(stringifyValue(value)));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private Object resolveMaybePathValue(Object value, Map<String, String> localValues) {
    if (!(value instanceof String s)) {
      return value;
    }
    String trimmed = s.trim();
    if (trimmed.startsWith("local.") || trimmed.startsWith("request.") || trimmed.startsWith("global.")) {
      return evaluateValue(trimmed, localValues);
    }
    return value;
  }

  private SourceRef loadSource(String reference, SourceRef parent, Map<String, SourceRef> cache) {
    String trimmed = reference == null ? "" : reference.trim();
    if (trimmed.isBlank()) {
      throw new IllegalArgumentException("Empty source reference");
    }

    if (looksLikeHttpUrl(trimmed)) {
      return loadRemoteSource(trimmed, cache);
    }
    if (parent != null && parent.url() != null) {
      URI resolved = parent.url().resolve(trimmed);
      if (looksLikeHttpUrl(resolved.toString())) {
        return loadRemoteSource(resolved.toString(), cache);
      }
    }

    Path path = Path.of(trimmed);
    if (!path.isAbsolute() && parent != null && parent.path() != null) {
      Path parentDir = parent.path().getParent();
      if (parentDir != null) {
        path = parentDir.resolve(path);
      }
    }
    Path normalized = path.normalize();
    try {
      return new SourceRef(normalized.toString(), Files.readString(normalized, StandardCharsets.UTF_8), null, normalized);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read local config: " + normalized, e);
    }
  }

  private static final SourceRef FAILED_REMOTE = new SourceRef("__failed__", "", URI.create("http://localhost"), null);

  private SourceRef loadRemoteSource(String url, Map<String, SourceRef> cache) {
    SourceRef result = cache.computeIfAbsent(url, u -> {
      try {
        return new SourceRef(u, httpFetchService.fetchText(u), URI.create(u), null);
      } catch (Exception e) {
        return FAILED_REMOTE;
      }
    });
    if (result == FAILED_REMOTE) {
      throw new IllegalStateException("Failed to fetch remote config: " + url);
    }
    return result;
  }

  private Map<String, Object> loadBaseRoot(String baseRef, SourceRef owner, Map<String, String> localValues, Map<String, SourceRef> cache) {
    if (baseRef == null || baseRef.isBlank()) {
      return Map.of();
    }
    try {
      SourceRef source = loadSource(baseRef, owner, cache);
      String rendered = String.join("\n", renderTemplateLines(source, localValues, new LinkedHashSet<>(), cache));
      Object root = tryLoadYaml(rendered);
      if (!(root instanceof Map<?, ?> map)) {
        return Map.of();
      }
      return normalizeMap(map);
    } catch (Exception e) {
      LOG.warnf("Ignore invalid clash rule base: source=%s reason=%s", baseRef, e.getMessage());
      return Map.of();
    }
  }

  private List<Map<String, Object>> extractGroupList(Map<String, Object> root) {
    Object groups = root.get("proxy-groups");
    if (groups == null) {
      groups = root.get("Proxy Group");
    }
    if (!(groups instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        out.add(normalizeMap(map));
      }
    }
    return out;
  }

  private List<String> extractRuleList(Map<String, Object> root) {
    Object rules = root.get("rules");
    if (rules == null) {
      rules = root.get("Rule");
    }
    if (!(rules instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object item : list) {
      if (item == null) {
        continue;
      }
      String line = String.valueOf(item).trim();
      if (!line.isBlank()) {
        out.add(line);
      }
    }
    return dedupeStrings(out);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> normalizeMap(Map<?, ?> map) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (entry.getKey() == null) {
        continue;
      }
      normalized.put(String.valueOf(entry.getKey()), normalizeYamlValue(entry.getValue()));
    }
    return normalized;
  }

  @SuppressWarnings("unchecked")
  private Object normalizeYamlValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return normalizeMap(map);
    }
    if (value instanceof List<?> list) {
      List<Object> normalized = new ArrayList<>();
      for (Object item : list) {
        normalized.add(normalizeYamlValue(item));
      }
      return normalized;
    }
    return value;
  }

  private Object tryLoadYaml(String body) {
    try {
      return yaml.load(body);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static boolean looksLikeHttpUrl(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      URI uri = URI.create(value.trim());
      return Objects.equals(uri.getScheme(), "http") || Objects.equals(uri.getScheme(), "https");
    } catch (Exception ignored) {
      return false;
    }
  }

  private static Integer parseInteger(String value, Integer fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static boolean looksLikeInteger(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    try {
      Integer.parseInt(value.trim());
      return true;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String sanitizeLine(String rawLine) {
    if (rawLine == null) {
      return "";
    }
    String line = rawLine.replace("\uFEFF", "").trim();
    if (line.startsWith(";") || line.startsWith("#") || line.startsWith("//")) {
      return "";
    }
    return line;
  }

  private static List<String> splitComma(String value) {
    List<String> parts = new ArrayList<>();
    for (String part : value.split(",")) {
      parts.add(part.trim());
    }
    return parts;
  }

  private static List<String> splitArguments(String source) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    boolean singleQuote = false;
    boolean doubleQuote = false;
    int start = 0;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '\'' && !doubleQuote) {
        singleQuote = !singleQuote;
        continue;
      }
      if (c == '"' && !singleQuote) {
        doubleQuote = !doubleQuote;
        continue;
      }
      if (singleQuote || doubleQuote) {
        continue;
      }
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth = Math.max(0, depth - 1);
      } else if (c == ',' && depth == 0) {
        out.add(source.substring(start, i).trim());
        start = i + 1;
      }
    }
    out.add(source.substring(start).trim());
    return out;
  }

  private static String stripOuterParens(String value) {
    String current = value == null ? "" : value.trim();
    while (current.startsWith("(") && current.endsWith(")") && isBalanced(current.substring(1, current.length() - 1))) {
      current = current.substring(1, current.length() - 1).trim();
    }
    return current;
  }

  private static boolean isBalanced(String value) {
    int depth = 0;
    boolean singleQuote = false;
    boolean doubleQuote = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\'' && !doubleQuote) {
        singleQuote = !singleQuote;
        continue;
      }
      if (c == '"' && !singleQuote) {
        doubleQuote = !doubleQuote;
        continue;
      }
      if (singleQuote || doubleQuote) {
        continue;
      }
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
        if (depth < 0) {
          return false;
        }
      }
    }
    return depth == 0 && !singleQuote && !doubleQuote;
  }

  private static List<String> splitTopLevelByWord(String source, String word) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    boolean singleQuote = false;
    boolean doubleQuote = false;
    int start = 0;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '\'' && !doubleQuote) {
        singleQuote = !singleQuote;
        continue;
      }
      if (c == '"' && !singleQuote) {
        doubleQuote = !doubleQuote;
        continue;
      }
      if (singleQuote || doubleQuote) {
        continue;
      }
      if (c == '(') {
        depth++;
        continue;
      }
      if (c == ')') {
        depth = Math.max(0, depth - 1);
        continue;
      }
      if (depth == 0 && matchesWordAt(source, i, word)) {
        out.add(source.substring(start, i).trim());
        start = i + word.length();
      }
    }
    if (start == 0) {
      return List.of(source);
    }
    out.add(source.substring(start).trim());
    return out;
  }

  private static boolean matchesWordAt(String source, int index, String word) {
    int end = index + word.length();
    if (end > source.length()) {
      return false;
    }
    if (!source.regionMatches(true, index, word, 0, word.length())) {
      return false;
    }
    char before = index == 0 ? ' ' : source.charAt(index - 1);
    char after = end >= source.length() ? ' ' : source.charAt(end);
    return Character.isWhitespace(before) && Character.isWhitespace(after);
  }

  private static Comparison findComparison(String source) {
    int depth = 0;
    boolean singleQuote = false;
    boolean doubleQuote = false;
    for (int i = 0; i < source.length() - 1; i++) {
      char c = source.charAt(i);
      if (c == '\'' && !doubleQuote) {
        singleQuote = !singleQuote;
        continue;
      }
      if (c == '"' && !singleQuote) {
        doubleQuote = !doubleQuote;
        continue;
      }
      if (singleQuote || doubleQuote) {
        continue;
      }
      if (c == '(') {
        depth++;
        continue;
      }
      if (c == ')') {
        depth = Math.max(0, depth - 1);
        continue;
      }
      if (depth != 0) {
        continue;
      }
      String op = source.substring(i, i + 2);
      if ("==".equals(op) || "!=".equals(op)) {
        return new Comparison(source.substring(0, i).trim(), source.substring(i + 2).trim(), "!=".equals(op));
      }
    }
    return null;
  }

  private static boolean truthy(String value) {
    if (value == null) {
      return false;
    }
    return truthyObject(value.trim());
  }

  private static boolean truthyObject(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return false;
    }
    return !normalized.equals("false")
        && !normalized.equals("0")
        && !normalized.equals("off")
        && !normalized.equals("no");
  }

  private static boolean isBlankValue(Object value) {
    if (value == null) {
      return true;
    }
    return value instanceof String s && s.isBlank();
  }

  private static String stringifyValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static boolean isQuoted(String value) {
    return value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")));
  }

  private static String unquote(String value) {
    return isQuoted(value) ? value.substring(1, value.length() - 1) : value;
  }

  private static String extractQuotedValue(String value) {
    Matcher doubleQuoted = Pattern.compile("^\"(.*)\"$").matcher(value.trim());
    if (doubleQuoted.find()) {
      return doubleQuoted.group(1);
    }
    Matcher singleQuoted = Pattern.compile("^'(.*)'$").matcher(value.trim());
    if (singleQuoted.find()) {
      return singleQuoted.group(1);
    }
    return null;
  }

  private static String canonicalRuleMode(String prefix) {
    String normalized = prefix.toLowerCase(Locale.ROOT);
    if (normalized.equals("classic") || normalized.equals("classical") || normalized.equals("clash-classical")) {
      return normalized.startsWith("clash-") ? "clash-classic" : "classic";
    }
    return normalized;
  }

  private static List<ProxyNode> dedupeNodes(List<ProxyNode> nodes) {
    if (nodes == null || nodes.isEmpty()) {
      return List.of();
    }
    LinkedHashMap<String, ProxyNode> deduped = new LinkedHashMap<>();
    for (ProxyNode node : nodes) {
      if (node == null || node.name() == null || node.name().isBlank()) {
        continue;
      }
      String key = node.dedupKey() == null || node.dedupKey().isBlank() ? node.name() : node.dedupKey();
      deduped.putIfAbsent(key, node);
    }
    return new ArrayList<>(deduped.values());
  }

  private static List<String> dedupeStrings(List<String> values) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String value : values) {
      if (value == null || value.isBlank()) {
        continue;
      }
      out.add(value);
    }
    return new ArrayList<>(out);
  }

  private static List<String> mergeRules(List<String> baseRules, List<String> generatedRules) {
    List<String> merged = new ArrayList<>();
    merged.addAll(baseRules);
    merged.addAll(generatedRules);
    return dedupeStrings(merged);
  }

  private static boolean isNegated(String rawValue) {
    return rawValue != null && rawValue.trim().startsWith("!");
  }

  private static Set<Integer> parseIntSet(String rawValue) {
    Set<Integer> out = new LinkedHashSet<>();
    String normalized = rawValue == null ? "" : rawValue.trim();
    if (normalized.startsWith("!")) {
      normalized = normalized.substring(1).trim();
    }
    for (String part : splitComma(normalized)) {
      Integer value = parseInteger(part, null);
      if (value != null) {
        out.add(value);
      }
    }
    return out;
  }

  private static Set<String> parseStringSet(String rawValue) {
    Set<String> out = new LinkedHashSet<>();
    String normalized = rawValue == null ? "" : rawValue.trim();
    if (normalized.startsWith("!")) {
      normalized = normalized.substring(1).trim();
    }
    for (String part : splitComma(normalized)) {
      if (!part.isBlank()) {
        out.add(part.trim().toLowerCase(Locale.ROOT));
      }
    }
    return out;
  }

  private enum ImportKind {
    RULESET,
    GROUP
  }

  private record SourceRef(String identity, String body, URI url, Path path) {
  }

  private record RuleSetRef(String policy, String source, Integer interval, SourceRef owner) {
  }

  private record GroupSpec(
      String name,
      String type,
      List<String> selectors,
      String testUrl,
      Integer interval,
      Integer timeout,
      Integer tolerance,
      String strategy
  ) {
  }

  private record Comparison(String left, String right, boolean negated) {
  }

  private record ConditionalFrame(boolean parentActive, boolean active, boolean anyMatched) {
  }

  private static final class ParseState {
    private final Map<String, String> localValues = new LinkedHashMap<>();
    private final List<GroupSpec> groups = new ArrayList<>();
    private final List<RuleSetRef> rulesets = new ArrayList<>();
    private boolean enableRuleGenerator = true;
    private boolean overwriteOriginalRules = true;
    private String clashRuleBase;
    private SourceRef clashRuleBaseOwner;
  }

  private static String rootCauseMessage(Throwable t) {
    if (t == null) return "unknown";
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause.getClass().getSimpleName() + ": " + cause.getMessage();
  }
}
