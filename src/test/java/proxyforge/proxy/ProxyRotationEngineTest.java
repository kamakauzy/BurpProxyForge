package proxyforge.proxy;

import org.junit.jupiter.api.Test;
import proxyforge.models.ProxyForgeModels.ExtensionState;
import proxyforge.models.ProxyForgeModels.ProxyEntry;
import proxyforge.models.ProxyForgeModels.ProxyMode;
import proxyforge.models.ProxyForgeModels.ProviderType;
import proxyforge.models.ProxyForgeModels.RotationStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProxyRotationEngineTest
{
    @Test
    void chooseForwarderMatchesTargetHost()
    {
        ExtensionState state = new ExtensionState();
        state.settings.rotationStrategy = RotationStrategy.ROUND_ROBIN;
        ProxyEntry securti = ProxyEntry.forwarder(
            ProviderType.CLOUDFLARE_FLAREPROX,
            "flare-securti",
            "https://flare-securti.example.workers.dev/",
            "https://securti360.com");
        ProxyEntry other = ProxyEntry.forwarder(
            ProviderType.AWS_FIREPROX,
            "fire-api",
            "https://apiid.execute-api.us-east-1.amazonaws.com/proxy/",
            "https://api.example.com");
        state.proxies.add(securti);
        state.proxies.add(other);

        ProxyRotationEngine engine = new ProxyRotationEngine(state);
        assertEquals(securti.id, engine.chooseForwarder("securti360.com").proxy().id);
        assertEquals(other.id, engine.chooseForwarder("api.example.com").proxy().id);
        assertNull(engine.chooseForwarder("no-match.example").proxy());
    }

    @Test
    void chooseProxyIgnoresForwardersForLocalProxyLane()
    {
        ExtensionState state = new ExtensionState();
        state.settings.rotationStrategy = RotationStrategy.ROUND_ROBIN;
        state.proxies.add(ProxyEntry.forwarder(
            ProviderType.CLOUDFLARE_FLAREPROX,
            "flare-securti",
            "https://flare-securti.example.workers.dev/",
            "https://securti360.com"));

        ProxyRotationEngine engine = new ProxyRotationEngine(state);
        assertNull(engine.chooseProxy("securti360.com", false).proxy());

        ProxyEntry upstream = ProxyEntry.networkProxy(
            ProviderType.VPS_FORGE,
            ProxyMode.HTTP_PROXY,
            "upstream",
            "127.0.0.1",
            9001,
            "",
            "");
        state.proxies.add(upstream);
        assertEquals(upstream.id, engine.chooseProxy("securti360.com", false).proxy().id);
    }
}
