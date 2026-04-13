package com.substacker.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SubscriptionSettingsTest {

  @Test
  void shouldKeepSelectedCustomTemplateId() {
    SubscriptionTemplate template = new SubscriptionTemplate("tpl-1", "Remote Template", "https://example.com/a.ini");
    SubscriptionSettings settings = new SubscriptionSettings("tpl-1", List.of(template), true);

    assertEquals("tpl-1", settings.defaultConfigId());
    assertNotNull(settings.findTemplate("tpl-1"));
    assertEquals(true, settings.regionalGroupsUrlTestEnabled());
  }

  @Test
  void shouldFallbackToBuiltinDefaultWhenTemplateDoesNotExist() {
    SubscriptionSettings settings = new SubscriptionSettings(
        "missing-template",
        List.of(new SubscriptionTemplate("tpl-2", "Remote Template", "https://example.com/b.ini")),
        false
    );

    assertEquals("default", settings.defaultConfigId());
    assertNull(settings.findTemplate("missing-template"));
  }
}
