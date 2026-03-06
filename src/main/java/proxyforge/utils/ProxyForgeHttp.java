package proxyforge.utils;

import proxyforge.models.ProxyForgeModels;
import proxyforge.models.ProxyForgeModels.ProxyEntry;
import proxyforge.models.ProxyForgeModels.ValidationResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ProxyForgeHttp
{
    private ProxyForgeHttp()
    {
    }

    public static HttpClient newHttpClient(Duration timeout)
    {
        return HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public static HttpResponse<String> sendWithRetry(
        HttpClient client,
        HttpRequest request,
        int attempts,
        Duration backoff,
        ProxyForgeLogger logger) throws IOException, InterruptedException
    {
        IOException lastIo = null;
        InterruptedException lastInterrupted = null;

        for (int index = 1; index <= attempts; index++)
        {
            try
            {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            }
            catch (IOException exception)
            {
                lastIo = exception;
                logger.warn("HTTP request attempt " + index + " failed: " + exception.getMessage());
            }
            catch (InterruptedException exception)
            {
                lastInterrupted = exception;
                Thread.currentThread().interrupt();
                break;
            }

            sleep(backoff.multipliedBy(index));
        }

        if (lastInterrupted != null)
        {
            throw lastInterrupted;
        }

        throw lastIo == null ? new IOException("HTTP request failed without a captured exception") : lastIo;
    }

    public static ValidationResult validateProxy(ProxyEntry proxy, Duration timeout)
    {
        if (proxy == null)
        {
            return ValidationResult.failure("No proxy selected");
        }

        if (proxy.mock)
        {
            return ValidationResult.success(1L);
        }

        Instant start = Instant.now();

        try
        {
            if (proxy.proxyMode == ProxyForgeModels.ProxyMode.FORWARDER)
            {
                HttpClient client = newHttpClient(timeout);
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(proxy.forwarderBaseUrl))
                    .timeout(timeout)
                    .GET()
                    .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 500)
                {
                    return ValidationResult.success(Duration.between(start, Instant.now()).toMillis());
                }
                return ValidationResult.failure("Forwarder returned status " + response.statusCode());
            }

            Proxy javaProxy = proxy.proxyMode == ProxyForgeModels.ProxyMode.SOCKS5
                ? new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxy.endpointHost, proxy.endpointPort))
                : new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.endpointHost, proxy.endpointPort));

            try (Socket socket = new Socket(javaProxy))
            {
                socket.connect(new InetSocketAddress(proxy.endpointHost, proxy.endpointPort), (int) timeout.toMillis());
                socket.setSoTimeout((int) timeout.toMillis());
            }

            return ValidationResult.success(Duration.between(start, Instant.now()).toMillis());
        }
        catch (Exception exception)
        {
            return ValidationResult.failure(exception.getMessage());
        }
    }

    public static MultipartBody multipartBody(Map<String, String> textParts, Map<String, byte[]> binaryParts, String binaryContentType)
    {
        String boundary = "----ProxyForge" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder builder = new StringBuilder();

        for (Map.Entry<String, String> entry : textParts.entrySet())
        {
            builder.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(entry.getKey()).append("\"\r\n\r\n")
                .append(entry.getValue()).append("\r\n");
        }

        Map<String, byte[]> normalizedBinary = new LinkedHashMap<>(binaryParts);
        byte[] prefix = builder.toString().getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();

        try
        {
            output.write(prefix);
            for (Map.Entry<String, byte[]> entry : normalizedBinary.entrySet())
            {
                output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"; filename=\"" + entry.getKey() + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
                output.write(("Content-Type: " + binaryContentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(entry.getValue());
                output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException exception)
        {
            throw new IllegalStateException("Unable to build multipart request", exception);
        }

        return new MultipartBody(boundary, output.toByteArray());
    }

    private static void sleep(Duration duration)
    {
        try
        {
            TimeUnit.MILLISECONDS.sleep(Math.max(50L, duration.toMillis()));
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
    }

    public record MultipartBody(String boundary, byte[] body)
    {
    }
}
