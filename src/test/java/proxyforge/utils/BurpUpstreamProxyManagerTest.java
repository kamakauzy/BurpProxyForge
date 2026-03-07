package proxyforge.utils;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BurpUpstreamProxyManagerTest
{
    @Test
    void createsManagedRuleInExpectedProjectOptionsPath()
    {
        ObjectNode root = BurpUpstreamProxyManager.parseOrCreate("");
        ArrayNode servers = BurpUpstreamProxyManager.ensureServers(root);
        servers.add(BurpUpstreamProxyManager.createManagedRule(root, 8081));

        ObjectNode upstream = (ObjectNode) root.path("project_options").path("connections").path("upstream_proxy");
        assertFalse(upstream.path("use_user_options").asBoolean(true));
        assertEquals(1, upstream.path("servers").size());
        assertEquals("127.0.0.1", upstream.path("servers").get(0).path("proxy_host").asText());
        assertEquals(8081, upstream.path("servers").get(0).path("proxy_port").asInt());
        assertEquals("*", upstream.path("servers").get(0).path("destination_host").asText());
    }

    @Test
    void removesOnlyManagedRules()
    {
        ObjectNode root = BurpUpstreamProxyManager.parseOrCreate("");
        ArrayNode servers = BurpUpstreamProxyManager.ensureServers(root);
        servers.add(BurpUpstreamProxyManager.createManagedRule(root, 8081));

        ObjectNode userRule = root.objectNode();
        userRule.put("proxy_host", "corp.proxy.local");
        userRule.put("proxy_port", 3128);
        userRule.put("enabled", true);
        userRule.put("destination_host", "internal.example");
        servers.add(userRule);

        BurpUpstreamProxyManager.removeManagedRules(servers);
        assertEquals(1, servers.size());
        assertEquals("corp.proxy.local", servers.get(0).path("proxy_host").asText());
        assertFalse(BurpUpstreamProxyManager.isManagedRule(servers.get(0)));
    }

    @Test
    void matchesManagedRuleForLocalhostOrLoopback()
    {
        ObjectNode root = BurpUpstreamProxyManager.parseOrCreate("");
        ObjectNode localRule = BurpUpstreamProxyManager.createManagedRule(root, 8081);
        assertTrue(BurpUpstreamProxyManager.isManagedRule(localRule));

        ObjectNode localhostRule = root.objectNode();
        localhostRule.put("destination_host", "*");
        localhostRule.put("proxy_host", "localhost");
        localhostRule.put("proxy_port", 8081);
        assertTrue(BurpUpstreamProxyManager.isManagedRule(localhostRule));
    }
}
