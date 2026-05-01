package com.substacker.util;

import java.net.URI;
import java.net.URISyntaxException;

public final class NameUtils {

  private NameUtils() {
  }

  public static String decorateName(String upstreamName, String name) {
    String prefix = (upstreamName == null || upstreamName.isBlank()) ? "Upstream" : upstreamName.trim();
    return prefix + " | " + (name == null ? "Unnamed" : name.trim());
  }

  public static String fragmentOrHost(String rawFragment, String host) {
    String frag = rawFragment;
    if (frag != null && !frag.isBlank()) {
      return urlDecode(frag);
    }
    if (host != null && !host.isBlank()) {
      return host;
    }
    return "Unnamed";
  }

  public static String urlDecode(String s) {
    if (s == null) {
      return null;
    }
    return java.net.URLDecoder.decode(s.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
  }

  public static String safeUrl(String rawUrl) {
    if (rawUrl == null) {
      return "";
    }
    try {
      URI uri = new URI(rawUrl);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme();
      String host = uri.getHost() == null ? "" : uri.getHost();
      String path = uri.getPath() == null ? "" : uri.getPath();
      return scheme + "://" + host + path;
    } catch (URISyntaxException e) {
      return "(invalid url)";
    }
  }

  public static String safeName(String name, String fallback) {
    if (name != null && !name.isBlank()) {
      return name;
    }
    return fallback;
  }
}
