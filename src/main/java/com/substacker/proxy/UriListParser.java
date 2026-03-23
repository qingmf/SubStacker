package com.substacker.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class UriListParser implements UpstreamParser {
  private final ObjectMapper objectMapper;

  public UriListParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean supports(String contentType, String content) {
    String trimmed = content == null ? "" : content.trim();
    if (trimmed.startsWith("ss://") || trimmed.startsWith("vmess://") || trimmed.startsWith("trojan://")) {
      return true;
    }
    // Common: whole document is base64 of a URI list
    return looksLikeBase64(trimmed);
  }

  @Override
  public List<ProxyNode> parse(String upstreamName, String contentType, String content) {
    Objects.requireNonNull(content, "content");
    String decoded = maybeDecodeBase64(content.trim());
    List<ProxyNode> out = new ArrayList<>();
    decoded.lines().forEach(line -> {
      String s = line.trim();
      if (s.isEmpty()) {
        return;
      }
      if (s.startsWith("ss://")) {
        ProxyNode node = parseSs(upstreamName, s);
        if (node != null) {
          out.add(node);
        }
        return;
      }
      if (s.startsWith("trojan://")) {
        ProxyNode node = parseTrojan(upstreamName, s);
        if (node != null) {
          out.add(node);
        }
        return;
      }
      if (s.startsWith("vmess://")) {
        ProxyNode node = parseVmess(upstreamName, s);
        if (node != null) {
          out.add(node);
        }
      }
    });
    return out;
  }

  private ProxyNode parseSs(String upstreamName, String uri) {
    try {
      // ss://BASE64(method:password@host:port)#name or ss://BASE64#name
      String noScheme = uri.substring("ss://".length());
      String fragment = null;
      int hash = noScheme.indexOf('#');
      if (hash >= 0) {
        fragment = noScheme.substring(hash + 1);
        noScheme = noScheme.substring(0, hash);
      }
      // strip query (plugin etc)
      int q = noScheme.indexOf('?');
      if (q >= 0) {
        noScheme = noScheme.substring(0, q);
      }

      String decoded = new String(Base64.getDecoder().decode(padBase64(noScheme)), StandardCharsets.UTF_8);
      // method:password@host:port
      int at = decoded.lastIndexOf('@');
      String userInfo = decoded.substring(0, at);
      String hostPort = decoded.substring(at + 1);
      int colon1 = userInfo.indexOf(':');
      String method = userInfo.substring(0, colon1);
      String password = userInfo.substring(colon1 + 1);
      int colon2 = hostPort.lastIndexOf(':');
      String host = hostPort.substring(0, colon2);
      int port = Integer.parseInt(hostPort.substring(colon2 + 1));

      Map<String, Object> proxy = new LinkedHashMap<>();
      proxy.put("name", decorateName(upstreamName, fragment != null ? urlDecode(fragment) : host));
      proxy.put("type", "ss");
      proxy.put("server", host);
      proxy.put("port", port);
      proxy.put("cipher", method);
      proxy.put("password", password);

      String key = ("ss|" + host + "|" + port + "|" + password).toLowerCase(Locale.ROOT);
      return new ProxyNode((String) proxy.get("name"), proxy, key);
    } catch (Exception ignored) {
      return null;
    }
  }

  private ProxyNode parseTrojan(String upstreamName, String uri) {
    try {
      URI u = URI.create(uri);
      String password = u.getUserInfo();
      String host = u.getHost();
      int port = u.getPort() == -1 ? 443 : u.getPort();
      Map<String, String> q = parseQuery(u.getRawQuery());
      String name = decorateName(upstreamName, fragmentOrHost(u));

      Map<String, Object> proxy = new LinkedHashMap<>();
      proxy.put("name", name);
      proxy.put("type", "trojan");
      proxy.put("server", host);
      proxy.put("port", port);
      proxy.put("password", password);
      if (q.containsKey("sni")) {
        proxy.put("sni", q.get("sni"));
      }
      if (q.containsKey("peer")) {
        proxy.put("sni", q.get("peer")); // some subs use peer
      }
      if (q.containsKey("alpn")) {
        proxy.put("alpn", List.of(q.get("alpn").split(",")));
      }
      if (q.containsKey("skip-cert-verify")) {
        proxy.put("skip-cert-verify", Boolean.parseBoolean(q.get("skip-cert-verify")));
      }

      String key = ("trojan|" + host + "|" + port + "|" + password).toLowerCase(Locale.ROOT);
      return new ProxyNode(name, proxy, key);
    } catch (Exception ignored) {
      return null;
    }
  }

  private ProxyNode parseVmess(String upstreamName, String uri) {
    try {
      String payload = uri.substring("vmess://".length()).trim();
      String json = new String(Base64.getDecoder().decode(padBase64(payload)), StandardCharsets.UTF_8);
      JsonNode n = objectMapper.readTree(json);

      String host = text(n, "add");
      int port = intValue(n, "port", 443);
      String uuid = text(n, "id");
      int alterId = intValue(n, "aid", 0);
      String net = text(n, "net");
      String tls = text(n, "tls");
      String sni = text(n, "sni");
      String hostHeader = text(n, "host");
      String path = text(n, "path");
      String nameRaw = text(n, "ps");

      Map<String, Object> proxy = new LinkedHashMap<>();
      proxy.put("name", decorateName(upstreamName, nameRaw != null && !nameRaw.isBlank() ? nameRaw : host));
      proxy.put("type", "vmess");
      proxy.put("server", host);
      proxy.put("port", port);
      proxy.put("uuid", uuid);
      proxy.put("alterId", alterId);
      proxy.put("cipher", "auto");
      if (net != null && !net.isBlank()) {
        proxy.put("network", net);
      }
      if ("tls".equalsIgnoreCase(tls)) {
        proxy.put("tls", true);
      }
      if (sni != null && !sni.isBlank()) {
        proxy.put("servername", sni);
      }
      if (hostHeader != null && !hostHeader.isBlank()) {
        proxy.put("ws-headers", Map.of("Host", hostHeader));
      }
      if (path != null && !path.isBlank()) {
        proxy.put("ws-path", path);
      }

      String key = ("vmess|" + host + "|" + port + "|" + uuid).toLowerCase(Locale.ROOT);
      return new ProxyNode((String) proxy.get("name"), proxy, key);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String maybeDecodeBase64(String content) {
    String trimmed = content.trim();
    if (trimmed.startsWith("ss://") || trimmed.startsWith("vmess://") || trimmed.startsWith("trojan://")) {
      return content;
    }
    if (!looksLikeBase64(trimmed)) {
      return content;
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(padBase64(trimmed));
      String decoded = new String(bytes, StandardCharsets.UTF_8);
      if (decoded.contains("ss://") || decoded.contains("vmess://") || decoded.contains("trojan://")) {
        return decoded;
      }
      return content;
    } catch (Exception ignored) {
      return content;
    }
  }

  private static boolean looksLikeBase64(String s) {
    if (s == null || s.isBlank()) {
      return false;
    }
    if (s.contains("://")) {
      return false;
    }
    if (s.length() < 40) {
      return false;
    }
    return s.matches("^[A-Za-z0-9+/=_-\\r\\n]+$");
  }

  private static String padBase64(String s) {
    String normalized = s.replace('-', '+').replace('_', '/').replace("\r", "").replace("\n", "");
    int mod = normalized.length() % 4;
    if (mod == 0) {
      return normalized;
    }
    return normalized + "=".repeat(4 - mod);
  }

  private static String decorateName(String upstreamName, String name) {
    String prefix = (upstreamName == null || upstreamName.isBlank()) ? "Upstream" : upstreamName.trim();
    return prefix + " | " + (name == null ? "Unnamed" : name.trim());
  }

  private static String urlDecode(String s) {
    return URLDecoder.decode(s, StandardCharsets.UTF_8);
  }

  private static String fragmentOrHost(URI u) {
    String frag = u.getRawFragment();
    if (frag != null && !frag.isBlank()) {
      return urlDecode(frag);
    }
    if (u.getHost() != null) {
      return u.getHost();
    }
    return "Unnamed";
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> out = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return out;
    }
    for (String part : rawQuery.split("&")) {
      if (part.isBlank()) {
        continue;
      }
      int eq = part.indexOf('=');
      if (eq < 0) {
        out.put(urlDecode(part), "");
      } else {
        out.put(urlDecode(part.substring(0, eq)), urlDecode(part.substring(eq + 1)));
      }
    }
    return out;
  }

  private static String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    return v.asText();
  }

  private static int intValue(JsonNode node, String field, int defaultValue) {
    if (node == null) {
      return defaultValue;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return defaultValue;
    }
    if (v.isNumber()) {
      return v.asInt(defaultValue);
    }
    try {
      return Integer.parseInt(v.asText());
    } catch (Exception ignored) {
      return defaultValue;
    }
  }
}
