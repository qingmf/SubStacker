package com.substacker.service;

import com.substacker.store.DownloadCacheStore;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

@ApplicationScoped
public class HttpFetchService {
  private static final Logger LOG = Logger.getLogger(HttpFetchService.class);

  private final HttpClient httpClient;
  private final DownloadCacheStore cacheStore;

  public HttpFetchService(DownloadCacheStore cacheStore) {
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .proxy(new EnvProxySelector())
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();
    this.cacheStore = cacheStore;
  }

  public String fetchText(String url) {
    return fetchText(url, Map.of());
  }

  public String fetchText(String url, Map<String, String> headers) {
    Fetched fetched = fetchWithRetry(url, headers);
    return fetched.body();
  }

  public Fetched fetch(String url, Map<String, String> headers) {
    return fetchWithRetry(url, headers);
  }

  private Fetched fetchWithRetry(String url, Map<String, String> headers) {
    final int maxRetries = 3;
    Exception lastException = null;

    for (int attempt = 0; attempt < maxRetries; attempt++) {
      if (attempt > 0) {
        long delay = 500L * (1L << (attempt - 1)); // 500ms, 1000ms, 2000ms
        try {
          Thread.sleep(delay);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      try {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .setHeader("User-Agent", "Clash.Meta")
            .setHeader("Accept", "*/*")
            .GET();

        if (headers != null) {
          for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
              continue;
            }
            builder.setHeader(e.getKey(), e.getValue() == null ? "" : e.getValue());
          }
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          throw new IllegalStateException("HTTP " + response.statusCode());
        }
        String ct = response.headers().firstValue("content-type").orElse(null);
        String body = response.body() == null ? "" : response.body();
        cacheStore.put(url, body, ct);
        return new Fetched(ct, body);
      } catch (Exception e) {
        lastException = e;
        LOG.warnf("Fetch attempt %d/%d failed for %s: %s", attempt + 1, maxRetries, url, rootCauseMessage(e));
      }
    }

    var cached = cacheStore.get(url);
    if (cached != null && cached.body() != null) {
      LOG.warnf("Fetch failed for %s after %d attempts, using cached entry from %s", url, maxRetries, java.time.Instant.ofEpochMilli(cached.savedAt()));
      return new Fetched(cached.contentType(), cached.body());
    }
    throw new IllegalStateException("Failed to fetch: " + url + " (" + rootCauseMessage(lastException) + ")", lastException);
  }

  public record Fetched(String contentType, String body) {
    public String contentType() {
      return contentType == null ? null : contentType.toLowerCase(Locale.ROOT);
    }
  }

  private static String rootCauseMessage(Throwable t) {
    if (t == null) return "unknown";
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause.getClass().getSimpleName() + ": " + cause.getMessage();
  }

  private static class EnvProxySelector extends ProxySelector {
    @Override
    public List<Proxy> select(URI uri) {
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      String env = System.getenv(scheme + "_proxy");
      if (env == null || env.isBlank()) {
        env = System.getenv(scheme.toUpperCase(Locale.ROOT) + "_PROXY");
      }
      if (env == null || env.isBlank()) {
        return List.of(Proxy.NO_PROXY);
      }
      try {
        URI proxyUri = URI.create(env.trim());
        String host = proxyUri.getHost();
        int port = proxyUri.getPort();
        if (host == null || host.isBlank() || port <= 0) {
          return List.of(Proxy.NO_PROXY);
        }
        return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
      } catch (Exception e) {
        return List.of(Proxy.NO_PROXY);
      }
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
      LOG.debugf("Proxy connection failed for %s via %s: %s", uri, sa, ioe.getMessage());
    }
  }
}
