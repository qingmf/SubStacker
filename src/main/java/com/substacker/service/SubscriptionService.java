package com.substacker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.substacker.model.Upstream;
import com.substacker.model.UpstreamType;
import com.substacker.proxy.ClashYamlParser;
import com.substacker.proxy.ProxyAggregator;
import com.substacker.proxy.ProxyNode;
import com.substacker.proxy.ResolvedSubscriptionConfig;
import com.substacker.proxy.UpstreamParser;
import com.substacker.proxy.UriListParser;
import com.substacker.util.NameUtils;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@ApplicationScoped
public class SubscriptionService {
  private static final Logger LOG = Logger.getLogger(SubscriptionService.class);

  private final UpstreamService upstreamService;
  private final SubscriptionConfigResolverService subscriptionConfigResolverService;
  private final HttpFetchService httpFetchService;
  private final List<UpstreamParser> parsers;
  private final ProxyAggregator aggregator;
  private final Yaml yaml;
  private final ExecutorService sharedExecutor;

  public SubscriptionService(
      UpstreamService upstreamService,
      SubscriptionConfigResolverService subscriptionConfigResolverService,
      ObjectMapper objectMapper,
      HttpFetchService httpFetchService
  ) {
    this.upstreamService = upstreamService;
    this.subscriptionConfigResolverService = subscriptionConfigResolverService;
    this.httpFetchService = httpFetchService;
    this.parsers = List.of(
        new ClashYamlParser(),
        new UriListParser(objectMapper)
    );
    this.aggregator = new ProxyAggregator();
    this.yaml = new Yaml();
    this.sharedExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @PreDestroy
  void cleanup() {
    sharedExecutor.shutdownNow();
  }

  public String buildOpenClashYaml() {
    return buildOpenClashYaml(null);
  }

  public String buildOpenClashYaml(String requestedConfigId) {
    List<ProxyNode> nodes = collectAll();
    ResolvedSubscriptionConfig config = subscriptionConfigResolverService.resolve(requestedConfigId, nodes);
    return aggregator.toOpenClashYaml(nodes, config);
  }

  public List<ProxyNode> collectAll() {
    List<ProxyNode> out = new ArrayList<>();
    List<Upstream> upstreams = upstreamService.list();
    record Task(Upstream upstream, Future<List<ProxyNode>> future) {}
    List<Task> tasks = new ArrayList<>();
    for (int i = 0; i < upstreams.size(); i++) {
      Upstream u = upstreams.get(i);
      if (u == null || !u.enabled()) {
        continue;
      }
      final int idx = i;
      tasks.add(new Task(u, sharedExecutor.submit(() -> fetchAndParse(u, idx))));
    }
    for (Task t : tasks) {
      try {
        out.addAll(t.future().get());
      } catch (Exception e) {
        LOG.warnf("Upstream failed: name=%s url=%s", safeName(t.upstream()), safeUrl(t.upstream()));
      }
    }
    return out;
  }

  public CollectReport collectReport() {
    List<UpstreamReport> reports = new ArrayList<>();
    List<Upstream> upstreams = upstreamService.list();
    record Task(Upstream upstream, Future<List<ProxyNode>> future) {}
    List<Task> tasks = new ArrayList<>();
    for (int i = 0; i < upstreams.size(); i++) {
      Upstream u = upstreams.get(i);
      if (u == null) {
        continue;
      }
      if (!u.enabled()) {
        reports.add(UpstreamReport.disabled(safeName(u), safeUrl(u)));
        continue;
      }
      final int idx = i;
      tasks.add(new Task(u, sharedExecutor.submit(() -> fetchAndParse(u, idx))));
    }
    for (Task t : tasks) {
      Upstream u = t.upstream();
      try {
        List<ProxyNode> nodes = t.future().get();
        reports.add(UpstreamReport.ok(safeName(u), safeUrl(u), nodes.size()));
      } catch (Exception e) {
        Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
        reports.add(UpstreamReport.failed(safeName(u), safeUrl(u), cause.getClass().getSimpleName() + ": " + cause.getMessage()));
      }
    }
    return new CollectReport(reports);
  }

  public String previewFromBody(String upstreamName, UpstreamType type, String body, String contentType) {
    return previewFromBody(upstreamName, type, body, contentType, null);
  }

  public String previewFromBody(String upstreamName, UpstreamType type, String body, String contentType, String requestedConfigId) {
    UpstreamParser parser = chooseParser(contentType, body);
    if (parser == null) {
      return "";
    }
    List<ProxyNode> nodes = applySourceMetadata(parser.parse(upstreamName, contentType, body), upstreamName, null, null);
    ResolvedSubscriptionConfig config = subscriptionConfigResolverService.resolve(requestedConfigId, nodes);
    return aggregator.toOpenClashYaml(nodes, config);
  }

  private UpstreamParser chooseParser(String contentType, String body) {
    for (UpstreamParser p : parsers) {
      if (p.supports(contentType, body)) {
        return p;
      }
    }
    return null;
  }

  private List<ProxyNode> fetchAndParse(Upstream upstream, int sourceGroupId) throws Exception {
    HttpFetchService.Fetched fetched = httpFetchService.fetch(upstream.url(), upstream.headers());
    String sourceGroupName = safeName(upstream);
    List<ProxyNode> direct = applySourceMetadata(
        parseBody(upstream.name(), fetched.contentType(), fetched.body()),
        sourceGroupName,
        sourceGroupId,
        null
    );
    if (!direct.isEmpty()) {
      return direct;
    }

    // Some Clash configs have no `proxies`, but have `proxy-providers` with URLs.
    List<ProviderRef> providers = tryExtractClashProviderUrls(fetched.body());
    if (providers.isEmpty()) {
      return List.of();
    }

    List<ProxyNode> out = new ArrayList<>();
    for (ProviderRef providerRef : providers) {
      try {
        HttpFetchService.Fetched providerFetched = httpFetchService.fetch(providerRef.url(), upstream.headers());
        out.addAll(applySourceMetadata(
            parseBody(upstream.name(), providerFetched.contentType(), providerFetched.body()),
            sourceGroupName,
            sourceGroupId,
            providerRef.name()
        ));
      } catch (Exception ignored) {
        // ignore single provider failures
      }
    }
    return out;
  }

  private List<ProxyNode> parseBody(String upstreamName, String contentType, String body) {
    UpstreamParser parser = chooseParser(contentType, body);
    if (parser == null) {
      return List.of();
    }
    return parser.parse(upstreamName, contentType, body);
  }

  private List<ProviderRef> tryExtractClashProviderUrls(String body) {
    try {
      Object root = yaml.load(body);
      if (!(root instanceof Map<?, ?> rootMap)) {
        return List.of();
      }
      Object providersObj = rootMap.get("proxy-providers");
      if (!(providersObj instanceof Map<?, ?> providersMap)) {
        return List.of();
      }
      List<ProviderRef> providers = new ArrayList<>();
      for (Map.Entry<?, ?> entry : providersMap.entrySet()) {
        Object v = entry.getValue();
        if (!(v instanceof Map<?, ?> provider)) {
          continue;
        }
        Object url = provider.get("url");
        if (url == null) {
          continue;
        }
        String s = String.valueOf(url).trim();
        if (!s.isBlank()) {
          String name = entry.getKey() == null ? "provider" : String.valueOf(entry.getKey()).trim();
          providers.add(new ProviderRef(name.isBlank() ? "provider" : name, s));
        }
      }
      return providers;
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private List<ProxyNode> applySourceMetadata(
      List<ProxyNode> nodes,
      String sourceGroupName,
      Integer sourceGroupId,
      String sourceProviderName
  ) {
    if (nodes == null || nodes.isEmpty()) {
      return List.of();
    }
    List<ProxyNode> out = new ArrayList<>(nodes.size());
    for (ProxyNode node : nodes) {
      if (node == null) {
        continue;
      }
      out.add(node.withSource(sourceGroupName, sourceGroupId, sourceProviderName));
    }
    return out;
  }

  private record ProviderRef(String name, String url) {
  }

  public record UpstreamReport(String name, String url, boolean enabled, boolean ok, int nodeCount, String error) {
    static UpstreamReport disabled(String name, String url) {
      return new UpstreamReport(name, url, false, true, 0, null);
    }

    static UpstreamReport ok(String name, String url, int nodeCount) {
      return new UpstreamReport(name, url, true, true, nodeCount, null);
    }

    static UpstreamReport failed(String name, String url, String error) {
      return new UpstreamReport(name, url, true, false, 0, error);
    }
  }

  public record CollectReport(List<UpstreamReport> upstreams) {
  }

  private static String safeName(Upstream u) {
    return NameUtils.safeName(u.name(), u.id());
  }

  private static String safeUrl(Upstream u) {
    return NameUtils.safeUrl(u.url());
  }
}
