package com.substacker.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UriListParserTest {
  private final UriListParser parser = new UriListParser(new ObjectMapper());

  @Test
  void parsesBase64WrappedMixedAirportSubscription() {
    String body = String.join("\n", List.of(
        modernSsLink(),
        legacySsLink(),
        ssrLink(),
        vmessJsonLink(),
        trojanLink(),
        vlessLink(),
        hy2Link(),
        tuicLink()
    ));
    String wrappedSubscription = base64(body);

    assertTrue(parser.supports("text/plain", wrappedSubscription));

    List<ProxyNode> nodes = parser.parse("Airport", "text/plain", wrappedSubscription);
    assertEquals(8, nodes.size());
    assertHasType(nodes, "ss");
    assertHasType(nodes, "ssr");
    assertHasType(nodes, "vmess");
    assertHasType(nodes, "trojan");
    assertHasType(nodes, "vless");
    assertHasType(nodes, "hysteria2");
    assertHasType(nodes, "tuic");

    Map<String, Object> ss = findByName(nodes, "Airport | SS SIP002").clashProxy();
    assertEquals("v2ray-plugin", ss.get("plugin"));
    assertEquals(443, ss.get("port"));
    assertNotNull(ss.get("plugin-opts"));

    Map<String, Object> vless = findByName(nodes, "Airport | VLESS Node").clashProxy();
    assertEquals("ws", vless.get("network"));
    assertEquals(true, vless.get("tls"));
    assertNotNull(vless.get("ws-opts"));

    Map<String, Object> trojan = findByName(nodes, "Airport | Trojan Node").clashProxy();
    assertEquals("grpc", trojan.get("network"));
    assertNotNull(trojan.get("grpc-opts"));

    Map<String, Object> hy2 = findByName(nodes, "Airport | HY2 Node").clashProxy();
    assertEquals(true, hy2.get("skip-cert-verify"));
  }

  @Test
  void parsesUriStyleVmessLinks() {
    String uri = "vmess://123e4567-e89b-12d3-a456-426614174099@vmess-uri.example.com:443"
        + "?security=tls&type=ws&host=cdn.example.com&path=%2Fsocket&sni=sni.example.com#VMess URI";

    List<ProxyNode> nodes = parser.parse("Airport", "text/plain", uri);

    assertEquals(1, nodes.size());
    Map<String, Object> proxy = nodes.getFirst().clashProxy();
    assertEquals("vmess", proxy.get("type"));
    assertEquals(true, proxy.get("tls"));
    assertEquals("ws", proxy.get("network"));
    assertNotNull(proxy.get("ws-opts"));
  }

  private static void assertHasType(List<ProxyNode> nodes, String type) {
    assertTrue(nodes.stream().anyMatch(node -> type.equals(node.clashProxy().get("type"))));
  }

  private static ProxyNode findByName(List<ProxyNode> nodes, String name) {
    return nodes.stream()
        .filter(node -> name.equals(node.name()))
        .findFirst()
        .orElseThrow();
  }

  private static String modernSsLink() {
    String userInfo = base64UrlNoPad("aes-256-gcm:pass-ss");
    String plugin = URLEncoder.encode("v2ray-plugin;tls;host=cdn.example.com;path=/ss-ws", StandardCharsets.UTF_8);
    return "ss://" + userInfo + "@ss.example.com:443?plugin=" + plugin + "#SS SIP002";
  }

  private static String legacySsLink() {
    return "ss://" + base64("chacha20-ietf-poly1305:legacy-pass@legacy.example.com:8443") + "#SS Legacy";
  }

  private static String ssrLink() {
    String payload = "ssr.example.com:443:auth_aes128_md5:aes-256-cfb:tls1.2_ticket_auth:"
        + base64UrlNoPad("ssr-pass")
        + "/?remarks=" + URLEncoder.encode(base64UrlNoPad("SSR Node"), StandardCharsets.UTF_8)
        + "&obfsparam=" + URLEncoder.encode(base64UrlNoPad("cdn.example.com"), StandardCharsets.UTF_8);
    return "ssr://" + base64UrlNoPad(payload);
  }

  private static String vmessJsonLink() {
    String json = """
        {
          "v":"2",
          "ps":"VMess JSON",
          "add":"vmess.example.com",
          "port":"443",
          "id":"123e4567-e89b-12d3-a456-426614174000",
          "aid":"0",
          "scy":"auto",
          "net":"ws",
          "host":"ws.example.com",
          "path":"/vmess",
          "tls":"tls",
          "sni":"sni.example.com"
        }
        """;
    return "vmess://" + base64(json);
  }

  private static String trojanLink() {
    return "trojan://trojan-pass@trojan.example.com:443"
        + "?type=grpc&serviceName=trojan-svc&sni=trojan.example.com#Trojan Node";
  }

  private static String vlessLink() {
    return "vless://123e4567-e89b-12d3-a456-426614174001@vless.example.com:443"
        + "?encryption=none&security=tls&type=ws&host=vless-cdn.example.com&path=%2Fvless&sni=vless.example.com#VLESS Node";
  }

  private static String hy2Link() {
    return "hy2://hy2-pass@hy2.example.com:443"
        + "?sni=hy2.example.com&obfs=salamander&obfs-password=mask&insecure=1#HY2 Node";
  }

  private static String tuicLink() {
    return "tuic://123e4567-e89b-12d3-a456-426614174002:tuic-pass@tuic.example.com:443"
        + "?sni=tuic.example.com&congestion_control=bbr&alpn=h3#TUIC Node";
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String base64UrlNoPad(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
