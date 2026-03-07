package proxyforge.proxy;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForwarderRewriteHttpHandlerTest
{
    @Test
    void applyForwarderHeadersAddsOriginalHostHeaders()
    {
        HttpRequest request = fakeRequest(
            fakeService("securit360.com", 443, true),
            "/hello",
            "x=1",
            Map.of("Host", "securit360.com", "User-Agent", "test"));

        HttpRequest rewritten = ForwarderRewriteHttpHandler.applyForwarderHeaders(request, "securit360.com", true);

        assertEquals("/hello?x=1", rewritten.path());
        assertEquals("securit360.com", rewritten.headerValue("Host"));
        assertEquals("securit360.com", rewritten.headerValue("X-ProxyForge-Original-Host"));
        assertEquals("https", rewritten.headerValue("X-ProxyForge-Original-Scheme"));
    }

    private static HttpRequest fakeRequest(HttpService service, String pathWithoutQuery, String query, Map<String, String> headers)
    {
        record State(HttpService service, String pathWithoutQuery, String query, Map<String, String> headers)
        {
        }

        @SuppressWarnings("unchecked")
        java.util.function.Function<State, HttpRequest>[] create = new java.util.function.Function[1];
        create[0] = state -> (HttpRequest) Proxy.newProxyInstance(
            HttpRequest.class.getClassLoader(),
            new Class[]{HttpRequest.class},
            (proxy, method, args) ->
            {
                return switch (method.getName())
                {
                    case "httpService" -> state.service();
                    case "pathWithoutQuery" -> state.pathWithoutQuery();
                    case "query" -> state.query();
                    case "path" -> state.query() == null || state.query().isBlank()
                        ? state.pathWithoutQuery()
                        : state.pathWithoutQuery() + "?" + state.query();
                    case "headerValue" -> state.headers().get((String) args[0]);
                    case "withService" -> create[0].apply(new State((HttpService) args[0], state.pathWithoutQuery(), state.query(), new LinkedHashMap<>(state.headers())));
                    case "withPath" ->
                    {
                        String pathValue = (String) args[0];
                        String[] parts = pathValue.split("\\?", 2);
                        yield create[0].apply(new State(state.service(), parts[0], parts.length > 1 ? parts[1] : "", new LinkedHashMap<>(state.headers())));
                    }
                    case "withUpdatedHeader", "withAddedHeader" ->
                    {
                        Map<String, String> mutated = new LinkedHashMap<>(state.headers());
                        mutated.put((String) args[0], (String) args[1]);
                        yield create[0].apply(new State(state.service(), state.pathWithoutQuery(), state.query(), mutated));
                    }
                    case "withRemovedHeader" ->
                    {
                        Map<String, String> mutated = new LinkedHashMap<>(state.headers());
                        mutated.remove((String) args[0]);
                        yield create[0].apply(new State(state.service(), state.pathWithoutQuery(), state.query(), mutated));
                    }
                    case "toString" -> "FakeHttpRequest";
                    default -> throw new UnsupportedOperationException("Unsupported method in fake request: " + method.getName());
                };
            });

        return create[0].apply(new State(service, pathWithoutQuery, query, new LinkedHashMap<>(headers)));
    }

    private static HttpService fakeService(String host, int port, boolean secure)
    {
        return (HttpService) Proxy.newProxyInstance(
            HttpService.class.getClassLoader(),
            new Class[]{HttpService.class},
            (proxy, method, args) -> switch (method.getName())
            {
                case "host" -> host;
                case "port" -> port;
                case "secure" -> secure;
                case "toString" -> host + ":" + port;
                default -> throw new UnsupportedOperationException("Unsupported method in fake service: " + method.getName());
            });
    }
}
