package proxyforge.providers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderRegistryTest
{
    @Test
    void normalizeTargetUrlAddsHttpsSchemeWhenMissing()
    {
        assertEquals("https://securti360.com", ProviderRegistry.normalizeTargetUrl("securti360.com"));
        assertEquals("https://example.com/path", ProviderRegistry.normalizeTargetUrl("https://example.com/path/"));
    }

    @Test
    void normalizeWorkersSubdomainAcceptsFullWorkersDevUrl()
    {
        assertEquals("e71b8997b1-tl1", ProviderRegistry.normalizeWorkersSubdomain("https://e71b8997b1-tl1.workers.dev"));
        assertEquals("e71b8997b1-tl1", ProviderRegistry.normalizeWorkersSubdomain("e71b8997b1-tl1.workers.dev"));
        assertEquals("e71b8997b1-tl1", ProviderRegistry.normalizeWorkersSubdomain("e71b8997b1-tl1"));
    }

    @Test
    void normalizeWorkersSubdomainRejectsMalformedInput()
    {
        assertThrows(IllegalArgumentException.class, () -> ProviderRegistry.normalizeWorkersSubdomain("https://workers.dev/foo"));
        assertThrows(IllegalArgumentException.class, () -> ProviderRegistry.normalizeWorkersSubdomain("bad/subdomain"));
    }

    @Test
    void detectsCloudflarePlaceholderPage()
    {
        assertEquals(true, ProviderRegistry.looksLikeCloudflarePlaceholder("There is nothing here yet. Please check back again later."));
        assertEquals(false, ProviderRegistry.looksLikeCloudflarePlaceholder("<html><title>Home - SecurIT360</title></html>"));
    }
}
