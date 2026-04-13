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
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.yaml.snakeyaml.Yaml;

import java.net.URISyntaxException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@ApplicationScoped
public class SubscriptionService {
  private static final Logger LOG = Logger.getLogger(SubscriptionService.class);

  private final UpstreamService upstreamService;
  private final SubscriptionConfigResolverService subscriptionConfigResolverService;
  private final HttpClient httpClient;
  private final List<UpstreamParser> parsers;
  private final ProxyAggregator aggregator;
  private final Yaml yaml;

  public SubscriptionService(
      UpstreamService upstreamService,
      SubscriptionConfigResolverService subscriptionConfigResolverService,
      ObjectMapper objectMapper
  ) {
    this.upstreamService = upstreamService;
    this.subscriptionConfigResolverService = subscriptionConfigResolverService;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    this.parsers = List.of(
        new ClashYamlParser(),
        new UriListParser(objectMapper)
    );
    this.aggregator = new ProxyAggregator();
    this.yaml = new Yaml();
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
    for (int i = 0; i < upstreams.size(); i++) {
      Upstream u = upstreams.get(i);
      if (u == null || !u.enabled()) {
        continue;
      }
      try {
        out.addAll(fetchAndParse(u, i));
      } catch (Exception ignored) {
        // Upstream failures should not break the whole subscription generation.
        LOG.warnf("Upstream failed: name=%s url=%s", safeName(u), safeUrl(u));
      }
    }
    return out;
  }

  public CollectReport collectReport() {
    List<UpstreamReport> reports = new ArrayList<>();
    List<Upstream> upstreams = upstreamService.list();
    for (int i = 0; i < upstreams.size(); i++) {
      Upstream u = upstreams.get(i);
      if (u == null) {
        continue;
      }
      if (!u.enabled()) {
        reports.add(UpstreamReport.disabled(safeName(u), safeUrl(u)));
        continue;
      }
      try {
        List<ProxyNode> nodes = fetchAndParse(u, i);
        reports.add(UpstreamReport.ok(safeName(u), safeUrl(u), nodes.size()));
      } catch (Exception e) {
        reports.add(UpstreamReport.failed(safeName(u), safeUrl(u), e.getClass().getSimpleName() + ": " + e.getMessage()));
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
    Fetched fetched = fetch(upstream);
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
        Upstream provider = new Upstream(
            upstream.id(),
            upstream.name() + " | " + providerRef.name(),
            upstream.type(),
            providerRef.url(),
            true,
            upstream.headers()
        );
        Fetched providerFetched = fetch(provider);
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

  private Fetched fetch(Upstream u) throws Exception {
    if (u.type() == UpstreamType.CLASH_YAML_URL || u.type() == UpstreamType.URI_LIST_URL) {
      boolean hasUserAgent = hasHeader(u.headers(), "User-Agent");
      boolean hasAccept = hasHeader(u.headers(), "Accept");
      HttpRequest.Builder b = HttpRequest.newBuilder()
          .uri(URI.create(u.url()))
          .timeout(Duration.ofSeconds(20))
          .GET();
      if (!hasUserAgent) {
        b.header("User-Agent", "Clash.Meta");
      }
      if (!hasAccept) {
        b.header("Accept", "*/*");
      }
      if (u.headers() != null) {
        for (Map.Entry<String, String> e : u.headers().entrySet()) {
          if (e.getKey() == null || e.getKey().isBlank()) {
            continue;
          }
          b.header(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
      }
      HttpResponse<String> resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
        throw new IllegalStateException("HTTP " + resp.statusCode());
      }
      String ct = resp.headers().firstValue("content-type").orElse(null);
      return new Fetched(ct, resp.body() == null ? "" : resp.body());
    }
    throw new IllegalArgumentException("Unsupported upstream type: " + u.type());
  }

  private static boolean hasHeader(Map<String, String> headers, String name) {
    if (headers == null || headers.isEmpty()) {
      return false;
    }
    for (String key : headers.keySet()) {
      if (key != null && key.equalsIgnoreCase(name)) {
        return true;
      }
    }
    return false;
  }

  private record Fetched(String contentType, String body) {
    public String contentType() {
      return contentType == null ? null : contentType.toLowerCase(Locale.ROOT);
    }
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
    if (u.name() != null && !u.name().isBlank()) {
      return u.name();
    }
    return u.id();
  }

  private static String safeUrl(Upstream u) {
    if (u.url() == null) {
      return "";
    }
    try {
      URI uri = new URI(u.url());
      String scheme = uri.getScheme() == null ? "" : uri.getScheme();
      String host = uri.getHost() == null ? "" : uri.getHost();
      String path = uri.getPath() == null ? "" : uri.getPath();
      return scheme + "://" + host + path;
    } catch (URISyntaxException e) {
      return "(invalid url)";
    }
  }
}
