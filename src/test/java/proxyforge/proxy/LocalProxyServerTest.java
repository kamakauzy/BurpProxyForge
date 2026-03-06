package proxyforge.proxy;

import org.junit.jupiter.api.Test;
import proxyforge.models.ProxyForgeModels;
import proxyforge.models.ProxyForgeModels.ExtensionState;
import proxyforge.models.ProxyForgeModels.ProxyEntry;
import proxyforge.models.ProxyForgeModels.ProxyMode;
import proxyforge.models.ProxyForgeModels.ProviderType;
import proxyforge.models.ProxyForgeModels.RotationStrategy;
import proxyforge.utils.ProxyForgeLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProxyServerTest
{
    @Test
    void proxiesPlainHttpTrafficThroughSelectedUpstreamProxy() throws Exception
    {
        int upstreamPort = freePort();
        int localProxyPort = freePort();

        AtomicReference<String> upstreamRequestLine = new AtomicReference<>();
        CountDownLatch upstreamObserved = new CountDownLatch(1);

        Thread upstreamServer = Thread.ofVirtual().start(() ->
        {
            try (ServerSocket serverSocket = new ServerSocket(upstreamPort);
                 Socket socket = serverSocket.accept())
            {
                InputStream inputStream = socket.getInputStream();
                String request = readHeader(inputStream);
                upstreamRequestLine.set(request.lines().findFirst().orElse(""));
                upstreamObserved.countDown();

                OutputStream outputStream = socket.getOutputStream();
                byte[] body = "via-upstream".getBytes(StandardCharsets.UTF_8);
                outputStream.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
                outputStream.write(body);
                outputStream.flush();
            }
            catch (IOException ignored)
            {
            }
        });

        ExtensionState state = new ExtensionState();
        state.settings.rotationStrategy = RotationStrategy.ROUND_ROBIN;
        ProxyEntry entry = ProxyEntry.networkProxy(
            ProviderType.VPS_FORGE,
            ProxyMode.HTTP_PROXY,
            "test-upstream",
            "127.0.0.1",
            upstreamPort,
            "",
            "",
            false);
        state.proxies.add(entry);

        ProxyRotationEngine engine = new ProxyRotationEngine(state);
        try (LocalProxyServer server = new LocalProxyServer(engine, new ProxyForgeLogger(null), () -> { }))
        {
            server.start(localProxyPort, false);
            try (Socket client = new Socket("127.0.0.1", localProxyPort))
            {
                OutputStream outputStream = client.getOutputStream();
                outputStream.write("GET http://example.test/hello?x=1 HTTP/1.1\r\nHost: example.test\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
                outputStream.flush();

                String response = readAll(client.getInputStream());
                assertTrue(response.contains("via-upstream"));
            }
        }

        assertTrue(upstreamObserved.await(5, TimeUnit.SECONDS));
        assertEquals("GET http://example.test/hello?x=1 HTTP/1.1", upstreamRequestLine.get());
        assertTrue(entry.requestsServed >= 1);
        upstreamServer.join(2_000L);
    }

    @Test
    void roundRobinSelectsDifferentActiveProxies()
    {
        ExtensionState state = new ExtensionState();
        state.settings.rotationStrategy = RotationStrategy.ROUND_ROBIN;
        state.proxies.add(ProxyEntry.networkProxy(ProviderType.VPS_FORGE, ProxyMode.HTTP_PROXY, "one", "127.0.0.1", 9001, "", "", true));
        state.proxies.add(ProxyEntry.networkProxy(ProviderType.VPS_FORGE, ProxyMode.HTTP_PROXY, "two", "127.0.0.1", 9002, "", "", true));

        ProxyRotationEngine engine = new ProxyRotationEngine(state);
        ProxyEntry first = engine.chooseProxy("a.example").proxy();
        ProxyEntry second = engine.chooseProxy("b.example").proxy();

        assertNotNull(first);
        assertNotNull(second);
        assertTrue(!first.id.equals(second.id));
    }

    @Test
    void connectSelectionSkipsForwarders()
    {
        ExtensionState state = new ExtensionState();
        state.settings.rotationStrategy = RotationStrategy.ROUND_ROBIN;
        state.proxies.add(ProxyEntry.forwarder(
            ProviderType.CLOUDFLARE_FLAREPROX,
            "flareprox",
            "https://flare.example.workers.dev/",
            "https://example.com",
            true));
        ProxyEntry connectCapable = ProxyEntry.networkProxy(
            ProviderType.VPS_FORGE,
            ProxyMode.HTTP_PROXY,
            "http-proxy",
            "127.0.0.1",
            9001,
            "",
            "",
            true);
        state.proxies.add(connectCapable);

        ProxyRotationEngine engine = new ProxyRotationEngine(state);
        assertEquals(connectCapable.id, engine.chooseProxy("example.com", true).proxy().id);
    }

    @Test
    void connectSelectionReturnsNoProxyWhenOnlyForwardersExist()
    {
        ExtensionState state = new ExtensionState();
        state.settings.rotationStrategy = RotationStrategy.ROUND_ROBIN;
        state.proxies.add(ProxyEntry.forwarder(
            ProviderType.CLOUDFLARE_FLAREPROX,
            "flareprox",
            "https://flare.example.workers.dev/",
            "https://example.com",
            true));

        ProxyRotationEngine engine = new ProxyRotationEngine(state);
        assertNull(engine.chooseProxy("example.com", true).proxy());
    }

    private static String readAll(InputStream inputStream) throws IOException
    {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        inputStream.transferTo(outputStream);
        return outputStream.toString(StandardCharsets.ISO_8859_1);
    }

    private static String readHeader(InputStream inputStream) throws IOException
    {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        int previous = -1;
        int lineBreaks = 0;
        while (true)
        {
            int current = inputStream.read();
            if (current == -1)
            {
                break;
            }
            outputStream.write(current);
            if ((previous == '\r' && current == '\n') || (previous == '\n' && current == '\n'))
            {
                if (++lineBreaks >= 2)
                {
                    break;
                }
            }
            else if (current != '\r')
            {
                lineBreaks = 0;
            }
            previous = current;
        }
        return outputStream.toString(StandardCharsets.ISO_8859_1);
    }

    private static int freePort() throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            return socket.getLocalPort();
        }
    }
}
