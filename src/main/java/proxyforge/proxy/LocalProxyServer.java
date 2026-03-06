package proxyforge.proxy;

import proxyforge.models.ProxyForgeModels;
import proxyforge.models.ProxyForgeModels.ProxyEntry;
import proxyforge.models.ProxyForgeModels.ProxyMode;
import proxyforge.models.ProxyForgeModels.RouteDecision;
import proxyforge.utils.ProxyForgeLogger;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalProxyServer implements AutoCloseable
{
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(30);

    private final ProxyRotationEngine rotationEngine;
    private final ProxyForgeLogger logger;
    private final Runnable stateChangeCallback;
    private final AtomicBoolean running = new AtomicBoolean();

    private ServerSocketChannel serverSocketChannel;
    private Thread acceptThread;
    private int port;
    private String bindHost = "127.0.0.1";

    public LocalProxyServer(ProxyRotationEngine rotationEngine, ProxyForgeLogger logger, Runnable stateChangeCallback)
    {
        this.rotationEngine = Objects.requireNonNull(rotationEngine, "rotationEngine");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.stateChangeCallback = Objects.requireNonNullElse(stateChangeCallback, () -> { });
    }

    public synchronized void start(int port, boolean allowExternalBind) throws IOException
    {
        if (running.get())
        {
            stop();
        }

        this.port = port;
        this.bindHost = allowExternalBind ? "0.0.0.0" : "127.0.0.1";
        this.serverSocketChannel = ServerSocketChannel.open();
        this.serverSocketChannel.bind(new InetSocketAddress(bindHost, port));
        this.running.set(true);
        this.acceptThread = Thread.ofPlatform().name("proxyforge-proxy-accept").start(this::acceptLoop);
        logger.info("Local ProxyForge listener started on " + bindHost + ":" + port);
    }

    public synchronized void stop()
    {
        running.set(false);
        if (serverSocketChannel != null)
        {
            try
            {
                serverSocketChannel.close();
            }
            catch (IOException ignored)
            {
            }
        }
        if (acceptThread != null)
        {
            acceptThread.interrupt();
        }
        logger.info("Local ProxyForge listener stopped.");
    }

    public boolean isRunning()
    {
        return running.get();
    }

    public int port()
    {
        return port;
    }

    @Override
    public void close()
    {
        stop();
    }

    private void acceptLoop()
    {
        while (running.get())
        {
            try
            {
                SocketChannel clientChannel = serverSocketChannel.accept();
                Thread.ofVirtual().name("proxyforge-proxy-client").start(() -> handleClient(clientChannel));
            }
            catch (IOException exception)
            {
                if (running.get())
                {
                    logger.error("Proxy accept loop failed", exception);
                }
            }
        }
    }

    private void handleClient(SocketChannel clientChannel)
    {
        try (clientChannel)
        {
            clientChannel.configureBlocking(true);
            Socket clientSocket = clientChannel.socket();
            clientSocket.setSoTimeout((int) SOCKET_TIMEOUT.toMillis());

            InputStream clientInput = Channels.newInputStream(clientChannel);
            OutputStream clientOutput = Channels.newOutputStream(clientChannel);

            byte[] requestHeader = readHttpHeader(clientInput);
            if (requestHeader.length == 0)
            {
                return;
            }

            ParsedRequest parsedRequest = ParsedRequest.parse(requestHeader);
            RouteDecision routeDecision = rotationEngine.chooseProxy(parsedRequest.host());
            ProxyEntry proxyEntry = routeDecision.proxy();
            if (proxyEntry == null)
            {
                sendSimpleResponse(clientOutput, 502, "No active proxies in pool");
                return;
            }

            if ("CONNECT".equalsIgnoreCase(parsedRequest.method()))
            {
                handleConnect(clientInput, clientOutput, parsedRequest, proxyEntry);
            }
            else
            {
                handleHttpRequest(clientInput, clientOutput, parsedRequest, proxyEntry);
            }
        }
        catch (Exception exception)
        {
            logger.error("Proxy request handling failed", exception);
        }
        finally
        {
            stateChangeCallback.run();
        }
    }

    private void handleConnect(InputStream clientInput, OutputStream clientOutput, ParsedRequest request, ProxyEntry proxyEntry) throws IOException
    {
        if (!proxyEntry.supportsConnect())
        {
            sendSimpleResponse(clientOutput, 501, "Selected forwarder cannot tunnel CONNECT traffic");
            rotationEngine.recordFailure(proxyEntry, "Forwarder does not support CONNECT");
            return;
        }

        String targetHost = request.host();
        int targetPort = request.port();
        if (targetPort <= 0)
        {
            targetPort = 443;
        }

        if (proxyEntry.proxyMode == ProxyMode.HTTP_PROXY)
        {
            try (Socket upstreamSocket = new Socket())
            {
                upstreamSocket.connect(new InetSocketAddress(proxyEntry.endpointHost, proxyEntry.endpointPort), (int) SOCKET_TIMEOUT.toMillis());
                upstreamSocket.setSoTimeout((int) SOCKET_TIMEOUT.toMillis());
                InputStream upstreamInput = upstreamSocket.getInputStream();
                OutputStream upstreamOutput = upstreamSocket.getOutputStream();

                StringBuilder builder = new StringBuilder()
                    .append("CONNECT ").append(targetHost).append(":").append(targetPort).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(targetHost).append(":").append(targetPort).append("\r\n")
                    .append("Proxy-Connection: Keep-Alive\r\n");
                if (!proxyEntry.username.isBlank())
                {
                    builder.append("Proxy-Authorization: Basic ").append(basicAuth(proxyEntry.username, proxyEntry.password)).append("\r\n");
                }
                builder.append("\r\n");
                upstreamOutput.write(builder.toString().getBytes(StandardCharsets.ISO_8859_1));
                upstreamOutput.flush();

                byte[] connectResponse = readHttpHeader(upstreamInput);
                String responseText = new String(connectResponse, StandardCharsets.ISO_8859_1);
                if (!responseText.startsWith("HTTP/1.1 200") && !responseText.startsWith("HTTP/1.0 200"))
                {
                    clientOutput.write(connectResponse);
                    clientOutput.flush();
                    rotationEngine.recordFailure(proxyEntry, "Upstream CONNECT failed");
                    return;
                }

                clientOutput.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                clientOutput.flush();
                rotationEngine.recordSuccess(proxyEntry);
                relayBidirectional(clientInput, clientOutput, upstreamInput, upstreamOutput);
            }
            return;
        }

        Proxy socksProxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyEntry.endpointHost, proxyEntry.endpointPort));
        try (Socket upstreamSocket = new Socket(socksProxy))
        {
            upstreamSocket.connect(new InetSocketAddress(targetHost, targetPort), (int) SOCKET_TIMEOUT.toMillis());
            upstreamSocket.setSoTimeout((int) SOCKET_TIMEOUT.toMillis());
            clientOutput.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            clientOutput.flush();
            rotationEngine.recordSuccess(proxyEntry);
            relayBidirectional(clientInput, clientOutput, upstreamSocket.getInputStream(), upstreamSocket.getOutputStream());
        }
        catch (IOException exception)
        {
            rotationEngine.recordFailure(proxyEntry, exception.getMessage());
            throw exception;
        }
    }

    private void handleHttpRequest(InputStream clientInput, OutputStream clientOutput, ParsedRequest request, ProxyEntry proxyEntry) throws IOException
    {
        switch (proxyEntry.proxyMode)
        {
            case HTTP_PROXY -> handleViaHttpProxy(clientInput, clientOutput, request, proxyEntry);
            case SOCKS5 -> handleViaSocksProxy(clientInput, clientOutput, request, proxyEntry);
            case FORWARDER -> handleViaForwarder(clientInput, clientOutput, request, proxyEntry);
            default -> throw new IOException("Unsupported proxy mode " + proxyEntry.proxyMode);
        }
    }

    private void handleViaHttpProxy(InputStream clientInput, OutputStream clientOutput, ParsedRequest request, ProxyEntry proxyEntry) throws IOException
    {
        try (Socket upstreamSocket = new Socket())
        {
            upstreamSocket.connect(new InetSocketAddress(proxyEntry.endpointHost, proxyEntry.endpointPort), (int) SOCKET_TIMEOUT.toMillis());
            upstreamSocket.setSoTimeout((int) SOCKET_TIMEOUT.toMillis());
            OutputStream upstreamOutput = upstreamSocket.getOutputStream();

            byte[] outboundHeader = request.rebuildHeader(
                request.absoluteRequestTarget(),
                proxyEntry.endpointHost,
                true,
                !proxyEntry.username.isBlank() ? basicAuth(proxyEntry.username, proxyEntry.password) : null,
                request.host());
            upstreamOutput.write(outboundHeader);
            relayRequestBody(clientInput, upstreamOutput, request);
            upstreamOutput.flush();

            relayResponse(upstreamSocket.getInputStream(), clientOutput);
            rotationEngine.recordSuccess(proxyEntry);
        }
        catch (IOException exception)
        {
            rotationEngine.recordFailure(proxyEntry, exception.getMessage());
            throw exception;
        }
    }

    private void handleViaSocksProxy(InputStream clientInput, OutputStream clientOutput, ParsedRequest request, ProxyEntry proxyEntry) throws IOException
    {
        Proxy socksProxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyEntry.endpointHost, proxyEntry.endpointPort));
        try (Socket upstreamSocket = new Socket(socksProxy))
        {
            upstreamSocket.connect(new InetSocketAddress(request.host(), request.port()), (int) SOCKET_TIMEOUT.toMillis());
            upstreamSocket.setSoTimeout((int) SOCKET_TIMEOUT.toMillis());
            OutputStream upstreamOutput = upstreamSocket.getOutputStream();

            byte[] outboundHeader = request.rebuildHeader(
                request.originFormTarget(),
                request.host(),
                false,
                null,
                request.host());
            upstreamOutput.write(outboundHeader);
            relayRequestBody(clientInput, upstreamOutput, request);
            upstreamOutput.flush();

            relayResponse(upstreamSocket.getInputStream(), clientOutput);
            rotationEngine.recordSuccess(proxyEntry);
        }
        catch (IOException exception)
        {
            rotationEngine.recordFailure(proxyEntry, exception.getMessage());
            throw exception;
        }
    }

    private void handleViaForwarder(InputStream clientInput, OutputStream clientOutput, ParsedRequest request, ProxyEntry proxyEntry) throws IOException
    {
        URI forwarderUri = URI.create(proxyEntry.forwarderBaseUrl);
        String outboundTarget = combineForwarderPath(forwarderUri, request.pathAndQuery());
        boolean secure = "https".equalsIgnoreCase(forwarderUri.getScheme());
        int port = forwarderUri.getPort() > 0 ? forwarderUri.getPort() : (secure ? 443 : 80);

        try (Socket upstreamSocket = secure
            ? sslSocket(forwarderUri.getHost(), port)
            : new Socket())
        {
            if (!secure)
            {
                upstreamSocket.connect(new InetSocketAddress(forwarderUri.getHost(), port), (int) SOCKET_TIMEOUT.toMillis());
            }
            upstreamSocket.setSoTimeout((int) SOCKET_TIMEOUT.toMillis());
            OutputStream upstreamOutput = upstreamSocket.getOutputStream();

            byte[] outboundHeader = request.rebuildHeader(outboundTarget, forwarderUri.getHost(), false, null, request.host());
            upstreamOutput.write(outboundHeader);
            relayRequestBody(clientInput, upstreamOutput, request);
            upstreamOutput.flush();

            relayResponse(upstreamSocket.getInputStream(), clientOutput);
            rotationEngine.recordSuccess(proxyEntry);
        }
        catch (IOException exception)
        {
            rotationEngine.recordFailure(proxyEntry, exception.getMessage());
            throw exception;
        }
    }

    private void relayRequestBody(InputStream clientInput, OutputStream upstreamOutput, ParsedRequest request) throws IOException
    {
        if (request.chunked())
        {
            relayChunkedBody(clientInput, upstreamOutput);
            return;
        }

        if (request.contentLength() <= 0)
        {
            return;
        }

        transfer(clientInput, upstreamOutput, request.contentLength());
    }

    private void relayResponse(InputStream upstreamInput, OutputStream clientOutput) throws IOException
    {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = upstreamInput.read(buffer)) != -1)
        {
            clientOutput.write(buffer, 0, read);
            clientOutput.flush();
        }
    }

    private void relayBidirectional(InputStream clientInput, OutputStream clientOutput, InputStream upstreamInput, OutputStream upstreamOutput)
    {
        Thread left = Thread.ofVirtual().name("proxyforge-pump-upstream").start(() -> pump(clientInput, upstreamOutput));
        Thread right = Thread.ofVirtual().name("proxyforge-pump-downstream").start(() -> pump(upstreamInput, clientOutput));
        try
        {
            left.join();
            right.join();
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void pump(InputStream inputStream, OutputStream outputStream)
    {
        byte[] buffer = new byte[8192];
        int read;
        try
        {
            while ((read = inputStream.read(buffer)) != -1)
            {
                outputStream.write(buffer, 0, read);
                outputStream.flush();
            }
        }
        catch (IOException ignored)
        {
        }
        finally
        {
            try
            {
                outputStream.flush();
            }
            catch (IOException ignored)
            {
            }
        }
    }

    private static void transfer(InputStream inputStream, OutputStream outputStream, long bytes) throws IOException
    {
        byte[] buffer = new byte[8192];
        long remaining = bytes;
        while (remaining > 0)
        {
            int read = inputStream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1)
            {
                break;
            }
            outputStream.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void relayChunkedBody(InputStream inputStream, OutputStream outputStream) throws IOException
    {
        while (true)
        {
            String line = readLine(inputStream);
            outputStream.write(line.getBytes(StandardCharsets.ISO_8859_1));
            int size = Integer.parseInt(line.trim().split(";", 2)[0], 16);
            if (size == 0)
            {
                String trailers = readLine(inputStream);
                outputStream.write(trailers.getBytes(StandardCharsets.ISO_8859_1));
                break;
            }
            transfer(inputStream, outputStream, size + 2L);
        }
    }

    private static byte[] readHttpHeader(InputStream inputStream) throws IOException
    {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        int previous = -1;
        int matched = 0;
        while (outputStream.size() < MAX_HEADER_BYTES)
        {
            int current = inputStream.read();
            if (current == -1)
            {
                break;
            }
            outputStream.write(current);
            if ((previous == '\r' && current == '\n') || (previous == '\n' && current == '\n'))
            {
                if (++matched >= 2)
                {
                    break;
                }
            }
            else if (current != '\r')
            {
                matched = 0;
            }
            previous = current;
        }
        return outputStream.toByteArray();
    }

    private static String readLine(InputStream inputStream) throws IOException
    {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        int previous = -1;
        while (true)
        {
            int current = inputStream.read();
            if (current == -1)
            {
                break;
            }
            outputStream.write(current);
            if (previous == '\r' && current == '\n')
            {
                break;
            }
            previous = current;
        }
        return outputStream.toString(StandardCharsets.ISO_8859_1);
    }

    private static String basicAuth(String username, String password)
    {
        return java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static void sendSimpleResponse(OutputStream outputStream, int statusCode, String body) throws IOException
    {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 " + statusCode + " " + reason(statusCode) + "\r\n"
            + "Content-Type: text/plain; charset=utf-8\r\n"
            + "Content-Length: " + payload.length + "\r\n"
            + "Connection: close\r\n\r\n";
        outputStream.write(response.getBytes(StandardCharsets.ISO_8859_1));
        outputStream.write(payload);
        outputStream.flush();
    }

    private static String reason(int statusCode)
    {
        return switch (statusCode)
        {
            case 200 -> "OK";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            default -> "ProxyForge";
        };
    }

    private static Socket sslSocket(String host, int port) throws IOException
    {
        SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
        socket.startHandshake();
        return socket;
    }

    private static String combineForwarderPath(URI forwarderUri, String pathAndQuery)
    {
        String basePath = forwarderUri.getPath() == null || forwarderUri.getPath().isBlank() ? "/" : forwarderUri.getPath();
        if (!basePath.endsWith("/"))
        {
            basePath = basePath + "/";
        }
        String normalizedPath = pathAndQuery.startsWith("/") ? pathAndQuery.substring(1) : pathAndQuery;
        return basePath + normalizedPath;
    }

    static final class ParsedRequest
    {
        private final String method;
        private final String requestTarget;
        private final String version;
        private final List<Header> headers;
        private final String host;
        private final int port;
        private final long contentLength;
        private final boolean chunked;

        private ParsedRequest(String method, String requestTarget, String version, List<Header> headers, String host, int port, long contentLength, boolean chunked)
        {
            this.method = method;
            this.requestTarget = requestTarget;
            this.version = version;
            this.headers = headers;
            this.host = host;
            this.port = port;
            this.contentLength = contentLength;
            this.chunked = chunked;
        }

        public static ParsedRequest parse(byte[] headerBytes)
        {
            String text = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String[] lines = text.split("\r\n");
            if (lines.length == 0)
            {
                throw new IllegalArgumentException("Invalid request");
            }

            String[] parts = lines[0].split(" ", 3);
            if (parts.length < 3)
            {
                throw new IllegalArgumentException("Invalid request line: " + lines[0]);
            }

            List<Header> headers = new ArrayList<>();
            String host = "";
            int port = 80;
            long contentLength = 0L;
            boolean chunked = false;

            for (int index = 1; index < lines.length; index++)
            {
                String line = lines[index];
                if (line.isEmpty())
                {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon <= 0)
                {
                    continue;
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.add(new Header(name, value));

                if (name.equalsIgnoreCase("Host"))
                {
                    host = value;
                }
                else if (name.equalsIgnoreCase("Content-Length"))
                {
                    try
                    {
                        contentLength = Long.parseLong(value);
                    }
                    catch (NumberFormatException ignored)
                    {
                        contentLength = 0L;
                    }
                }
                else if (name.equalsIgnoreCase("Transfer-Encoding") && value.toLowerCase(Locale.ROOT).contains("chunked"))
                {
                    chunked = true;
                }
            }

            String requestTarget = parts[1];
            if ("CONNECT".equalsIgnoreCase(parts[0]))
            {
                String[] authority = requestTarget.split(":", 2);
                host = authority[0];
                port = authority.length > 1 ? parsePort(authority[1], 443) : 443;
            }
            else if (requestTarget.startsWith("http://") || requestTarget.startsWith("https://"))
            {
                URI uri = URI.create(requestTarget);
                host = uri.getHost();
                port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            }
            else
            {
                String[] authority = host.split(":", 2);
                host = authority[0];
                port = authority.length > 1 ? parsePort(authority[1], 80) : 80;
            }

            return new ParsedRequest(parts[0], requestTarget, parts[2], headers, host, port, contentLength, chunked);
        }

        public String method()
        {
            return method;
        }

        public String host()
        {
            return host;
        }

        public int port()
        {
            return port;
        }

        public long contentLength()
        {
            return contentLength;
        }

        public boolean chunked()
        {
            return chunked;
        }

        public String pathAndQuery()
        {
            if (requestTarget.startsWith("http://") || requestTarget.startsWith("https://"))
            {
                URI uri = URI.create(requestTarget);
                String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
                return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
            }
            return requestTarget;
        }

        public String originFormTarget()
        {
            return pathAndQuery();
        }

        public String absoluteRequestTarget()
        {
            if (requestTarget.startsWith("http://") || requestTarget.startsWith("https://"))
            {
                return requestTarget;
            }

            String scheme = port == 443 ? "https" : "http";
            return scheme + "://" + host + ((port == 80 || port == 443) ? "" : ":" + port) + requestTarget;
        }

        public byte[] rebuildHeader(String outboundTarget, String outboundHost, boolean includeProxyConnection, String proxyAuthorization, String originalHost)
        {
            StringBuilder builder = new StringBuilder()
                .append(method).append(' ').append(outboundTarget).append(' ').append(version).append("\r\n");

            boolean hostWritten = false;
            boolean connectionWritten = false;
            boolean proxyAuthWritten = false;

            for (Header header : headers)
            {
                if (header.name.equalsIgnoreCase("Host"))
                {
                    builder.append("Host: ").append(outboundHost).append("\r\n");
                    hostWritten = true;
                }
                else if (header.name.equalsIgnoreCase("Connection") || header.name.equalsIgnoreCase("Proxy-Connection"))
                {
                    if (!connectionWritten)
                    {
                        builder.append("Connection: close\r\n");
                        if (includeProxyConnection)
                        {
                            builder.append("Proxy-Connection: close\r\n");
                        }
                        connectionWritten = true;
                    }
                }
                else if (header.name.equalsIgnoreCase("Proxy-Authorization"))
                {
                    if (proxyAuthorization != null && !proxyAuthorization.isBlank())
                    {
                        builder.append("Proxy-Authorization: Basic ").append(proxyAuthorization).append("\r\n");
                        proxyAuthWritten = true;
                    }
                }
                else
                {
                    builder.append(header.name).append(": ").append(header.value).append("\r\n");
                }
            }

            if (!hostWritten)
            {
                builder.append("Host: ").append(outboundHost).append("\r\n");
            }
            if (!connectionWritten)
            {
                builder.append("Connection: close\r\n");
                if (includeProxyConnection)
                {
                    builder.append("Proxy-Connection: close\r\n");
                }
            }
            if (proxyAuthorization != null && !proxyAuthorization.isBlank() && !proxyAuthWritten)
            {
                builder.append("Proxy-Authorization: Basic ").append(proxyAuthorization).append("\r\n");
            }

            builder.append("X-ProxyForge-Original-Host: ").append(originalHost).append("\r\n")
                .append("\r\n");
            return builder.toString().getBytes(StandardCharsets.ISO_8859_1);
        }

        private static int parsePort(String raw, int fallback)
        {
            try
            {
                return Integer.parseInt(raw);
            }
            catch (NumberFormatException ignored)
            {
                return fallback;
            }
        }
    }

    static final class Header
    {
        private final String name;
        private final String value;

        private Header(String name, String value)
        {
            this.name = name;
            this.value = value;
        }
    }
}
