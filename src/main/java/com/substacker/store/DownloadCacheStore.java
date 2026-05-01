package com.substacker.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.substacker.config.AppConfig;
import com.substacker.model.DownloadCache;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class DownloadCacheStore {
  private static final String FILE_NAME = "download-cache.json";

  private final ObjectMapper objectMapper;
  private final Path cacheFile;
  private final Map<String, DownloadCache.CachedEntry> entries;
  private final Duration ttl;
  private final ScheduledExecutorService flushExecutor;

  @Inject
  public DownloadCacheStore(ObjectMapper objectMapper, AppConfig config) {
    this(objectMapper, Path.of(config.dataDir()).resolve(FILE_NAME), config.downloadCacheTtl());
  }

  public DownloadCacheStore(ObjectMapper objectMapper, Path cacheFile) {
    this(objectMapper, cacheFile, Duration.ZERO);
  }

  public DownloadCacheStore(ObjectMapper objectMapper, Path cacheFile, Duration ttl) {
    this.objectMapper = objectMapper;
    this.cacheFile = cacheFile;
    this.ttl = ttl;
    this.entries = load().entries();
    this.flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "cache-flusher");
      t.setDaemon(true);
      return t;
    });
    this.flushExecutor.scheduleAtFixedRate(this::flush, 5, 30, TimeUnit.SECONDS);
  }

  @PreDestroy
  void cleanup() {
    flushExecutor.shutdownNow();
  }

  private synchronized DownloadCache load() {
    try {
      if (!Files.exists(cacheFile)) {
        return DownloadCache.empty();
      }
      DownloadCache cache = objectMapper.readValue(cacheFile.toFile(), DownloadCache.class);
      return evictExpired(cache);
    } catch (Exception e) {
      return DownloadCache.empty();
    }
  }

  private DownloadCache evictExpired(DownloadCache cache) {
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      return cache;
    }
    long cutoff = Instant.now().minusMillis(ttl.toMillis()).toEpochMilli();
    Map<String, DownloadCache.CachedEntry> active = new ConcurrentHashMap<>();
    for (Map.Entry<String, DownloadCache.CachedEntry> entry : cache.entries().entrySet()) {
      if (entry.getValue().savedAt() >= cutoff) {
        active.put(entry.getKey(), entry.getValue());
      }
    }
    return new DownloadCache(active);
  }

  public synchronized void save() {
    flush();
  }

  private void flush() {
    try {
      Files.createDirectories(cacheFile.getParent());
      Path tmp = cacheFile.resolveSibling(FILE_NAME + ".tmp");
      synchronized (this) {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new DownloadCache(entries));
      }
      Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception ignored) {
      // flush errors are non-fatal; next flush will retry
    }
  }

  public DownloadCache.CachedEntry get(String url) {
    DownloadCache.CachedEntry entry = entries.get(url);
    if (entry == null) {
      return null;
    }
    if (ttl != null && !ttl.isZero() && !ttl.isNegative()
        && System.currentTimeMillis() - entry.savedAt() > ttl.toMillis()) {
      entries.remove(url);
      return null;
    }
    return entry;
  }

  public void put(String url, String body, String contentType) {
    entries.put(url, new DownloadCache.CachedEntry(body, contentType, System.currentTimeMillis()));
  }
}
