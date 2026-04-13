package com.substacker.service;

import com.substacker.proxy.ProxyNode;
import com.substacker.proxy.ResolvedSubscriptionConfig;
import com.substacker.model.SubscriptionSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionConfigResolverServiceTest {

  @Test
  void shouldResolveImportsRelativeFilesAndMergeBaseRules() throws Exception {
    Path dir = Files.createTempDirectory("substacker-config-test");
    Path groups = dir.resolve("groups.txt");
    Path rules = dir.resolve("rules.list");
    Path base = dir.resolve("base.yaml");
    Path main = dir.resolve("main.ini");

    Files.writeString(groups, "GLOBAL`select`[]DIRECT`[]ALL\n");
    Files.writeString(rules, "netflix.com\n");
    Files.writeString(base, """
        mixed-port: 9999
        rules:
          - DOMAIN-SUFFIX,keep-base.example,DIRECT
        """);
    Files.writeString(main, """
        [template]
        clash.new_field_name=true

        [custom]
        overwrite_original_rules=false
        clash_rule_base=base.yaml
        custom_proxy_group=!!import:groups.txt
        ruleset=Streaming,domain:rules.list,86400
        ruleset=GLOBAL,[]FINAL
        """);

    SubscriptionConfigResolverService resolver = new SubscriptionConfigResolverService(null);
    List<ProxyNode> nodes = List.of(
        node("HK-01", "hk", "Main", 0, null),
        node("JP-01", "jp", "Main", 0, null)
    );

    ResolvedSubscriptionConfig config = resolver.resolve(main.toString(), nodes);

    assertEquals(9999, ((Number) config.rootTemplate().get("mixed-port")).intValue());
    assertEquals(List.of("GLOBAL"), config.proxyGroups().stream().map(group -> String.valueOf(group.get("name"))).toList());
    assertEquals(List.of("DIRECT", "HK-01", "JP-01"), config.proxyGroups().get(0).get("proxies"));
    assertEquals(
        List.of(
            "DOMAIN-SUFFIX,keep-base.example,DIRECT",
            "DOMAIN-SUFFIX,netflix.com,Streaming",
            "MATCH,GLOBAL"
        ),
        config.rules()
    );
  }

  @Test
  void shouldHonorTemplateConditionalsAndSourceSelectors() throws Exception {
    Path dir = Files.createTempDirectory("substacker-selector-test");
    Path main = dir.resolve("selector.ini");

    Files.writeString(main, """
        [template]
        feature=true

        [custom]
        {% if local.feature == "true" %}
        custom_proxy_group=OnlyFirst`select`!!GROUPID=0
        {% endif %}
        custom_proxy_group=OnlyAlphaProvider`select`!!PROVIDER=alpha
        """);

    SubscriptionConfigResolverService resolver = new SubscriptionConfigResolverService(null);
    List<ProxyNode> nodes = List.of(
        node("HK-01", "hk", "Main", 0, "alpha"),
        node("JP-01", "jp", "Backup", 1, "beta"),
        node("US-01", "us", "Backup", 1, "alpha")
    );

    ResolvedSubscriptionConfig config = resolver.resolve(main.toString(), nodes);

    assertEquals(List.of("OnlyFirst", "OnlyAlphaProvider"), config.proxyGroups().stream().map(group -> String.valueOf(group.get("name"))).toList());
    assertEquals(List.of("HK-01"), config.proxyGroups().get(0).get("proxies"));
    assertEquals(List.of("HK-01", "US-01"), config.proxyGroups().get(1).get("proxies"));
    assertTrue(config.rules().contains("MATCH,GLOBAL"));
  }

  @Test
  void quotedRegexSelectorShouldMatchNodesInsteadOfBecomingLiteralProxyName() throws Exception {
    Path dir = Files.createTempDirectory("substacker-quoted-regex-test");
    Path main = dir.resolve("quoted.ini");

    Files.writeString(main, """
        [custom]
        custom_proxy_group=福利节点`select`'(?=.*(福利|0.5x))'
        """);

    SubscriptionConfigResolverService resolver = new SubscriptionConfigResolverService(null);
    List<ProxyNode> nodes = List.of(
        node("机场A | 福利 0.5x", "a", "Main", 0, null),
        node("机场B | 正常 1x", "b", "Main", 0, null)
    );

    ResolvedSubscriptionConfig config = resolver.resolve(main.toString(), nodes);

    assertEquals(List.of("机场A | 福利 0.5x"), config.proxyGroups().getFirst().get("proxies"));
  }

  @Test
  void bracketPrefixedQuotedRegexSelectorShouldStillMatchNodes() throws Exception {
    Path dir = Files.createTempDirectory("substacker-bracket-regex-test");
    Path main = dir.resolve("bracket.ini");

    Files.writeString(main, """
        [custom]
        custom_proxy_group=福利节点`select`[]'(?=.*(0.5|0.5倍|0.5x|福利))'
        """);

    SubscriptionConfigResolverService resolver = new SubscriptionConfigResolverService(null);
    List<ProxyNode> nodes = List.of(
        node("机场A | 福利 0.5x", "a", "Main", 0, null),
        node("机场B | 正常 1x", "b", "Main", 0, null)
    );

    ResolvedSubscriptionConfig config = resolver.resolve(main.toString(), nodes);

    assertEquals(List.of("机场A | 福利 0.5x"), config.proxyGroups().getFirst().get("proxies"));
  }

  @Test
  void shouldSkipUnsupportedRuleTypesAndNormalizeSupportedAliases() throws Exception {
    Path dir = Files.createTempDirectory("substacker-rule-normalize-test");
    Path rules = dir.resolve("rules.list");
    Path main = dir.resolve("rules.ini");

    Files.writeString(rules, """
        URL-REGEX,^https?:\\/\\/www\\.amazon\\.com\\/(Amazon-Video|gp\\/video)\\/
        HOST-SUFFIX,netflix.com
        FINAL
        """);
    Files.writeString(main, """
        [custom]
        ruleset=国外媒体,classical:rules.list
        """);

    SubscriptionConfigResolverService resolver = new SubscriptionConfigResolverService(null);
    ResolvedSubscriptionConfig config = resolver.resolve(main.toString(), List.of());

    assertEquals(
        List.of(
            "DOMAIN-SUFFIX,netflix.com,国外媒体",
            "MATCH,国外媒体"
        ),
        config.rules()
    );
  }

  @Test
  void shouldConvertRegionalSelectGroupsToUrlTestWhenSettingEnabled() throws Exception {
    Path dir = Files.createTempDirectory("substacker-region-group-test");
    Path main = dir.resolve("region.ini");

    Files.writeString(main, """
        [custom]
        custom_proxy_group=香港节点`select`[]ALL
        custom_proxy_group=节点选择`select`[]DIRECT`[]香港节点
        """);

    SubscriptionSettingsService settingsService = new SubscriptionSettingsService(null) {
      @Override
      public SubscriptionSettings current() {
        return new SubscriptionSettings("default", List.of(), true);
      }

      @Override
      public String findTemplateUrl(String templateId) {
        return null;
      }
    };

    SubscriptionConfigResolverService resolver = new SubscriptionConfigResolverService(settingsService);
    List<ProxyNode> nodes = List.of(
        node("机场A | 香港 1", "a", "Main", 0, null),
        node("机场B | 香港 2", "b", "Main", 0, null)
    );

    ResolvedSubscriptionConfig config = resolver.resolve(main.toString(), nodes);

    assertEquals("url-test", config.proxyGroups().get(0).get("type"));
    assertEquals("select", config.proxyGroups().get(1).get("type"));
  }

  @Test
  void shouldMatchEnglishNamedNodesIntoChineseRegionalGroups() throws Exception {
    Path dir = Files.createTempDirectory("substacker-region-alias-test");
    Path main = dir.resolve("region-alias.ini");

    Files.writeString(main, """
        [custom]
        custom_proxy_group=香港节点`select`香港
        custom_proxy_group=台湾节点`select`台湾
        custom_proxy_group=日本节点`select`日本
        custom_proxy_group=美国节点`select`美国
        custom_proxy_group=新加坡节点`select`新加坡
        """);

    SubscriptionConfigResolverService resolver = new SubscriptionConfigResolverService(null);
    List<ProxyNode> nodes = List.of(
        node("流量光 | Hong Kong 03", "hk", "Main", 0, null),
        node("流量光 | Taiwan 01", "tw", "Main", 0, null),
        node("流量光 | Japan 01", "jp", "Main", 0, null),
        node("流量光 | United States 01", "us", "Main", 0, null),
        node("流量光 | Singapore 01", "sg", "Main", 0, null)
    );

    ResolvedSubscriptionConfig config = resolver.resolve(main.toString(), nodes);

    assertEquals(List.of("流量光 | Hong Kong 03"), config.proxyGroups().get(0).get("proxies"));
    assertEquals(List.of("流量光 | Taiwan 01"), config.proxyGroups().get(1).get("proxies"));
    assertEquals(List.of("流量光 | Japan 01"), config.proxyGroups().get(2).get("proxies"));
    assertEquals(List.of("流量光 | United States 01"), config.proxyGroups().get(3).get("proxies"));
    assertEquals(List.of("流量光 | Singapore 01"), config.proxyGroups().get(4).get("proxies"));
  }

  private static ProxyNode node(String name, String dedupKey, String sourceGroupName, Integer sourceGroupId, String sourceProviderName) {
    return new ProxyNode(
        name,
        Map.of(
            "name", name,
            "type", "trojan",
            "server", "example.com",
            "port", 443
        ),
        dedupKey,
        sourceGroupName,
        sourceGroupId,
        sourceProviderName
    );
  }
}
