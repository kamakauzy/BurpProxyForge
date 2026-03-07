package proxyforge.utils;

import burp.api.montoya.burpsuite.BurpSuite;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class BurpUpstreamProxyManager
{
    private static final String CONFIG_PATH = "project_options.connections.upstream_proxy";
    private static final String PROXY_HOST = "127.0.0.1";
    private static final String DESTINATION_HOST = "*";

    private final BurpSuite burpSuite;
    private final ProxyForgeLogger logger;

    public BurpUpstreamProxyManager(BurpSuite burpSuite, ProxyForgeLogger logger)
    {
        this.burpSuite = burpSuite;
        this.logger = logger;
    }

    public synchronized void syncManagedRule(boolean enabled, int port)
    {
        String existingJson = burpSuite.exportProjectOptionsAsJson(CONFIG_PATH);
        ObjectNode root = parseOrCreate(existingJson);
        ArrayNode servers = ensureServers(root);
        removeManagedRules(servers);

        if (enabled)
        {
            servers.add(createManagedRule(root, port));
            logger.info("Enabled Burp upstream proxy rule for ProxyForge on " + PROXY_HOST + ":" + port);
        }
        else
        {
            logger.info("Disabled Burp upstream proxy rule for ProxyForge.");
        }

        burpSuite.importProjectOptionsFromJson(ProxyForgeJson.write(root));
    }

    static ObjectNode parseOrCreate(String json)
    {
        if (json == null || json.isBlank())
        {
            return ProxyForgeJson.mapper().createObjectNode();
        }

        JsonNode parsed = ProxyForgeJson.read(json, JsonNode.class);
        if (parsed instanceof ObjectNode objectNode)
        {
            return objectNode;
        }
        return ProxyForgeJson.mapper().createObjectNode();
    }

    static ArrayNode ensureServers(ObjectNode root)
    {
        ObjectNode projectOptions = ensureObject(root, "project_options");
        ObjectNode connections = ensureObject(projectOptions, "connections");
        ObjectNode upstreamProxy = ensureObject(connections, "upstream_proxy");
        upstreamProxy.put("use_user_options", false);

        JsonNode existingServers = upstreamProxy.get("servers");
        if (existingServers instanceof ArrayNode arrayNode)
        {
            return arrayNode;
        }

        ArrayNode servers = ProxyForgeJson.mapper().createArrayNode();
        upstreamProxy.set("servers", servers);
        return servers;
    }

    static ObjectNode createManagedRule(ObjectNode root, int port)
    {
        ObjectNode server = root.objectNode();
        server.put("proxy_port", port);
        server.put("proxy_host", PROXY_HOST);
        server.put("enabled", true);
        server.put("destination_host", DESTINATION_HOST);
        server.put("auth_type", "none");
        server.put("username", "");
        server.put("password", "");
        server.put("domain", "");
        server.put("domain_hostname", "");
        return server;
    }

    static void removeManagedRules(ArrayNode servers)
    {
        for (int index = servers.size() - 1; index >= 0; index--)
        {
            JsonNode server = servers.get(index);
            if (isManagedRule(server))
            {
                servers.remove(index);
            }
        }
    }

    static boolean isManagedRule(JsonNode node)
    {
        if (node == null || !node.isObject())
        {
            return false;
        }

        String destinationHost = node.path("destination_host").asText("");
        String proxyHost = node.path("proxy_host").asText("");

        return DESTINATION_HOST.equals(destinationHost)
            && ("127.0.0.1".equals(proxyHost) || "localhost".equalsIgnoreCase(proxyHost));
    }

    private static ObjectNode ensureObject(ObjectNode parent, String fieldName)
    {
        JsonNode child = parent.get(fieldName);
        if (child instanceof ObjectNode objectNode)
        {
            return objectNode;
        }

        ObjectNode created = parent.objectNode();
        parent.set(fieldName, created);
        return created;
    }
}
