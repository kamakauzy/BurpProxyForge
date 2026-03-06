package proxyforge.proxy;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.requests.HttpRequest;
import proxyforge.models.ProxyForgeModels.ProxyEntry;
import proxyforge.models.ProxyForgeModels.RouteDecision;
import proxyforge.utils.ProxyForgeLogger;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ForwarderRewriteHttpHandler implements HttpHandler
{
    private final ProxyRotationEngine rotationEngine;
    private final ProxyForgeLogger logger;
    private final Runnable stateChangeCallback;
    private final Map<Integer, ProxyEntry> rewrittenRequests = new ConcurrentHashMap<>();

    public ForwarderRewriteHttpHandler(ProxyRotationEngine rotationEngine, ProxyForgeLogger logger, Runnable stateChangeCallback)
    {
        this.rotationEngine = Objects.requireNonNull(rotationEngine, "rotationEngine");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.stateChangeCallback = Objects.requireNonNullElse(stateChangeCallback, () -> { });
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request)
    {
        String originalHost = request.httpService().host();
        if (rotationEngine.isKnownForwarderHost(originalHost))
        {
            return RequestToBeSentAction.continueWith(request);
        }

        RouteDecision routeDecision = rotationEngine.chooseForwarder(originalHost);
        ProxyEntry forwarder = routeDecision.proxy();
        if (forwarder == null)
        {
            return RequestToBeSentAction.continueWith(request);
        }

        try
        {
            URI forwarderUri = URI.create(forwarder.forwarderBaseUrl);
            HttpService forwarderService = HttpService.httpService(
                forwarderUri.getHost(),
                forwarderUri.getPort() > 0 ? forwarderUri.getPort() : ("https".equalsIgnoreCase(forwarderUri.getScheme()) ? 443 : 80),
                "https".equalsIgnoreCase(forwarderUri.getScheme()));

            String newPath = combineForwarderPath(forwarderUri, request.pathWithoutQuery(), request.query());
            HttpRequest rewritten = request.withService(forwarderService)
                .withPath(newPath)
                .withUpdatedHeader("Host", forwarderUri.getHost())
                .withRemovedHeader("Proxy-Connection")
                .withRemovedHeader("Proxy-Authorization")
                .withUpdatedHeader("X-ProxyForge-Original-Host", originalHost)
                .withUpdatedHeader("X-ProxyForge-Original-Scheme", request.httpService().secure() ? "https" : "http");

            rewrittenRequests.put(request.messageId(), forwarder);
            logger.info("Rewrote " + originalHost + " through forwarder " + forwarder.name + " -> " + forwarder.forwarderBaseUrl);
            return RequestToBeSentAction.continueWith(rewritten);
        }
        catch (Exception exception)
        {
            rotationEngine.recordFailure(forwarder, exception.getMessage());
            stateChangeCallback.run();
            logger.error("Unable to rewrite request through forwarder " + forwarder.name, exception);
            return RequestToBeSentAction.continueWith(request);
        }
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response)
    {
        ProxyEntry proxyEntry = rewrittenRequests.remove(response.messageId());
        if (proxyEntry != null)
        {
            if (response.statusCode() >= 500)
            {
                rotationEngine.recordFailure(proxyEntry, "Forwarder returned status " + response.statusCode());
            }
            else
            {
                rotationEngine.recordSuccess(proxyEntry);
            }
            stateChangeCallback.run();
        }
        return ResponseReceivedAction.continueWith(response);
    }

    private static String combineForwarderPath(URI forwarderUri, String pathWithoutQuery, String query)
    {
        String basePath = forwarderUri.getPath() == null || forwarderUri.getPath().isBlank() ? "/" : forwarderUri.getPath();
        if (!basePath.endsWith("/"))
        {
            basePath = basePath + "/";
        }

        String normalizedPath = pathWithoutQuery == null || pathWithoutQuery.isBlank()
            ? ""
            : (pathWithoutQuery.startsWith("/") ? pathWithoutQuery.substring(1) : pathWithoutQuery);
        String combined = basePath + normalizedPath;
        if (query != null && !query.isBlank())
        {
            combined = combined + "?" + query;
        }
        return combined;
    }
}
