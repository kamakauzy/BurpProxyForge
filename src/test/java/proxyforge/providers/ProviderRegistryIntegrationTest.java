package proxyforge.providers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import proxyforge.models.ProxyForgeModels.DeployRequest;
import proxyforge.models.ProxyForgeModels.ProviderResult;
import proxyforge.models.ProxyForgeModels.ProviderType;
import proxyforge.utils.ProxyForgeHttp;
import proxyforge.utils.ProxyForgeLogger;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRegistryIntegrationTest
{
    @Test
    void cloudflareDeployUploadsRenderedWorkerAndVerifiesPublishedEndpoint() throws Exception
    {
        AtomicReference<String> uploadedBody = new AtomicReference<>("");
        AtomicReference<String> uploadedScriptName = new AtomicReference<>("");

        try (LocalCloudflareFixture fixture = new LocalCloudflareFixture(uploadedBody, uploadedScriptName))
        {
            HttpClient client = ProxyForgeHttp.newHttpClient(Duration.ofSeconds(5));
            ProviderRegistry registry = new ProviderRegistry(
                new ProxyForgeLogger(null),
                client,
                ProviderRegistry.RuntimeConfig.forTests(
                    fixture.apiBaseUrl(),
                    (scriptName, workersSubdomain) -> fixture.workerEndpoint(scriptName)));

            ProviderResult result = registry.deploy(new DeployRequest(
                ProviderType.CLOUDFLARE_FLAREPROX,
                Map.of(
                    "apiToken", "test-token",
                    "accountId", "test-account",
                    "workersSubdomain", "example-subdomain",
                    "targetUrl", "securti360.com")));

            assertTrue(result.success(), result.message());
            assertNotNull(result.proxy());
            assertEquals(uploadedScriptName.get(), result.proxy().providerResourceId);
            assertTrue(uploadedBody.get().contains("https://securti360.com"));
            assertFalse(uploadedBody.get().contains("__DEFAULT_TARGET__"));
            assertEquals(1, fixture.subdomainPostCount.get());
            assertTrue(fixture.subdomainGetCount.get() >= 1);
            assertTrue(fixture.workerGetCount.get() >= 1);
        }
    }

    private static final class LocalCloudflareFixture implements AutoCloseable
    {
        private final HttpServer server;
        private final AtomicReference<String> uploadedBody;
        private final AtomicReference<String> uploadedScriptName;
        private final java.util.concurrent.atomic.AtomicInteger subdomainPostCount = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger subdomainGetCount = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger workerGetCount = new java.util.concurrent.atomic.AtomicInteger();

        private LocalCloudflareFixture(AtomicReference<String> uploadedBody, AtomicReference<String> uploadedScriptName) throws IOException
        {
            this.uploadedBody = uploadedBody;
            this.uploadedScriptName = uploadedScriptName;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.setExecutor(Executors.newCachedThreadPool());
            this.server.createContext("/client/v4/accounts/test-account/workers/scripts", this::handleScripts);
            this.server.createContext("/worker", this::handleWorker);
            this.server.start();
        }

        private String apiBaseUrl()
        {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/client/v4";
        }

        private String workerEndpoint(String scriptName)
        {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/worker/" + scriptName + "/";
        }

        private void handleScripts(HttpExchange exchange) throws IOException
        {
            String path = exchange.getRequestURI().getPath();
            String base = "/client/v4/accounts/test-account/workers/scripts";
            String suffix = path.substring(base.length());

            if ("PUT".equals(exchange.getRequestMethod()) && suffix.startsWith("/"))
            {
                String scriptName = suffix.substring(1);
                uploadedScriptName.set(scriptName);
                uploadedBody.set(readBody(exchange.getRequestBody()));
                writeJson(exchange, 200, "{\"success\":true,\"result\":{\"id\":\"" + scriptName + "\"}}");
                return;
            }

            if ("POST".equals(exchange.getRequestMethod()) && suffix.endsWith("/subdomain"))
            {
                subdomainPostCount.incrementAndGet();
                writeJson(exchange, 200, "{\"success\":true,\"result\":{\"enabled\":true,\"previews_enabled\":true}}");
                return;
            }

            if ("GET".equals(exchange.getRequestMethod()) && suffix.endsWith("/subdomain"))
            {
                subdomainGetCount.incrementAndGet();
                writeJson(exchange, 200, "{\"success\":true,\"result\":{\"enabled\":true,\"previews_enabled\":true}}");
                return;
            }

            writeJson(exchange, 404, "{\"success\":false,\"errors\":[{\"code\":404,\"message\":\"not found\"}]}");
        }

        private void handleWorker(HttpExchange exchange) throws IOException
        {
            workerGetCount.incrementAndGet();
            String body = uploadedBody.get();
            if (body.contains("__DEFAULT_TARGET__"))
            {
                writeText(exchange, 500, "ProxyForge worker target not configured");
                return;
            }
            if (!body.contains("https://securti360.com"))
            {
                writeText(exchange, 500, "Rendered target missing");
                return;
            }
            writeText(exchange, 200, "OK");
        }

        @Override
        public void close()
        {
            server.stop(0);
        }

        private static String readBody(InputStream inputStream) throws IOException
        {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        private static void writeJson(HttpExchange exchange, int status, String body) throws IOException
        {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            writeText(exchange, status, body);
        }

        private static void writeText(HttpExchange exchange, int status, String body) throws IOException
        {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
