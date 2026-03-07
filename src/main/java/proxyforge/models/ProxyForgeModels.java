package proxyforge.models;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class ProxyForgeModels
{
    private ProxyForgeModels()
    {
    }

    public enum ProviderType
    {
        AWS_FIREPROX("AWS Fireprox"),
        CLOUDFLARE_FLAREPROX("Cloudflare Flareprox"),
        VPS_FORGE("VPS Forge");

        private final String label;

        ProviderType(String label)
        {
            this.label = label;
        }

        public String label()
        {
            return label;
        }
    }

    public enum ProxyMode
    {
        FORWARDER("Forwarder"),
        HTTP_PROXY("HTTP Proxy"),
        SOCKS5("SOCKS5");

        private final String label;

        ProxyMode(String label)
        {
            this.label = label;
        }

        public String label()
        {
            return label;
        }
    }

    public enum ProxyStatus
    {
        DEPLOYING,
        ACTIVE,
        VALIDATING,
        FAILED,
        DELETING,
        STOPPED
    }

    public enum RotationStrategy
    {
        RANDOM("Random"),
        ROUND_ROBIN("Round-Robin"),
        LEAST_USED("Least-Used"),
        STICKY_PER_HOST("Sticky-per-host"),
        PER_SCOPE_RULE("Per-scope rule");

        private final String label;

        RotationStrategy(String label)
        {
            this.label = label;
        }

        public String label()
        {
            return label;
        }
    }

    public enum VpsVendor
    {
        DIGITALOCEAN("DigitalOcean"),
        LINODE("Linode"),
        AWS_EC2("AWS EC2");

        private final String label;

        VpsVendor(String label)
        {
            this.label = label;
        }

        public String label()
        {
            return label;
        }
    }

    public static final class ProviderFormState
    {
        public Map<String, String> fields = new LinkedHashMap<>();
    }

    public static final class ExtensionSettings
    {
        public int localProxyPort = 8081;
        public RotationStrategy rotationStrategy = RotationStrategy.ROUND_ROBIN;
        public boolean autoStartProxy = true;
        public boolean manageBurpUpstreamProxy = true;
        public boolean firstLaunch = true;
        public boolean persistSensitiveFields = false;
        public boolean allowExternalBind = false;
    }

    public static final class ExtensionState
    {
        public ExtensionSettings settings = new ExtensionSettings();
        public List<ProxyEntry> proxies = new ArrayList<>();
        public List<ScopeRule> scopeRules = new ArrayList<>();
        public Map<String, String> stickyAssignments = new LinkedHashMap<>();
        public Map<ProviderType, ProviderFormState> providerFormStates = new EnumMap<>(ProviderType.class);
    }

    public static final class ScopeRule
    {
        public String id = UUID.randomUUID().toString();
        public String pattern = "";
        public boolean regex;
        public String assignedProxyId;
        public ProviderType preferredProvider;

        public ScopeRule()
        {
        }

        public ScopeRule(String pattern, boolean regex, String assignedProxyId, ProviderType preferredProvider)
        {
            this.pattern = Objects.requireNonNullElse(pattern, "");
            this.regex = regex;
            this.assignedProxyId = assignedProxyId;
            this.preferredProvider = preferredProvider;
        }

        public boolean matches(String host)
        {
            if (host == null || host.isBlank() || pattern == null || pattern.isBlank())
            {
                return false;
            }

            if (regex)
            {
                try
                {
                    return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(host).find();
                }
                catch (PatternSyntaxException ignored)
                {
                    return false;
                }
            }

            String normalized = pattern.trim().toLowerCase();
            String lowerHost = host.toLowerCase();

            if (normalized.startsWith("*."))
            {
                return lowerHost.endsWith(normalized.substring(1));
            }

            return lowerHost.equals(normalized) || lowerHost.endsWith("." + normalized);
        }
    }

    public static final class ProxyEntry
    {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public ProviderType providerType = ProviderType.AWS_FIREPROX;
        public ProxyMode proxyMode = ProxyMode.HTTP_PROXY;
        public ProxyStatus status = ProxyStatus.DEPLOYING;
        public String providerResourceId = "";
        public String endpointHost = "";
        public int endpointPort;
        public String endpointScheme = "http";
        public String forwarderBaseUrl = "";
        public String targetBaseUrl = "";
        public String username = "";
        public String password = "";
        public boolean enabled = true;
        public Instant createdAt = Instant.now();
        public Instant lastValidatedAt;
        public Instant lastUsedAt;
        public long requestsServed;
        public long successfulRequests;
        public long failedRequests;
        public String lastError = "";
        public Map<String, String> metadata = new LinkedHashMap<>();

        public ProxyEntry()
        {
        }

        public static ProxyEntry forwarder(ProviderType providerType, String name, String forwarderBaseUrl, String targetBaseUrl)
        {
            ProxyEntry entry = new ProxyEntry();
            entry.providerType = providerType;
            entry.proxyMode = ProxyMode.FORWARDER;
            entry.name = name;
            entry.forwarderBaseUrl = Objects.requireNonNullElse(forwarderBaseUrl, "");
            entry.targetBaseUrl = Objects.requireNonNullElse(targetBaseUrl, "");
            entry.status = ProxyStatus.ACTIVE;
            entry.endpointScheme = "https";
            return entry;
        }

        public static ProxyEntry networkProxy(
            ProviderType providerType,
            ProxyMode proxyMode,
            String name,
            String host,
            int port,
            String username,
            String password)
        {
            ProxyEntry entry = new ProxyEntry();
            entry.providerType = providerType;
            entry.proxyMode = proxyMode;
            entry.name = name;
            entry.endpointHost = Objects.requireNonNullElse(host, "");
            entry.endpointPort = port;
            entry.username = Objects.requireNonNullElse(username, "");
            entry.password = Objects.requireNonNullElse(password, "");
            entry.status = ProxyStatus.ACTIVE;
            entry.endpointScheme = proxyMode == ProxyMode.SOCKS5 ? "socks5" : "http";
            return entry;
        }

        public String displayEndpoint()
        {
            if (proxyMode == ProxyMode.FORWARDER)
            {
                return forwarderBaseUrl;
            }

            return endpointHost + ":" + endpointPort;
        }

        public boolean supportsConnect()
        {
            return proxyMode != ProxyMode.FORWARDER;
        }

        public boolean isForwarder()
        {
            return proxyMode == ProxyMode.FORWARDER;
        }

        public String targetHost()
        {
            if (targetBaseUrl == null || targetBaseUrl.isBlank())
            {
                return "";
            }

            try
            {
                URI uri = URI.create(targetBaseUrl);
                return Objects.requireNonNullElse(uri.getHost(), "");
            }
            catch (IllegalArgumentException ignored)
            {
                return "";
            }
        }

        public void markValidation(boolean success, String message)
        {
            lastValidatedAt = Instant.now();
            status = success ? ProxyStatus.ACTIVE : ProxyStatus.FAILED;
            lastError = success ? "" : Objects.requireNonNullElse(message, "Validation failed");
        }

        public void markRequestSuccess()
        {
            requestsServed++;
            successfulRequests++;
            lastUsedAt = Instant.now();
            status = ProxyStatus.ACTIVE;
        }

        public void markRequestFailure(String message)
        {
            requestsServed++;
            failedRequests++;
            lastUsedAt = Instant.now();
            status = ProxyStatus.FAILED;
            lastError = Objects.requireNonNullElse(message, "Unknown failure");
        }
    }

    public record DeployRequest(ProviderType providerType, Map<String, String> fields)
    {
        public String field(String key)
        {
            return fields == null ? null : fields.get(key);
        }
    }

    public record ProviderResult(boolean success, String message, ProxyEntry proxy, List<ProxyEntry> proxies)
    {
        public static ProviderResult success(String message, ProxyEntry proxy)
        {
            return new ProviderResult(true, message, proxy, proxy == null ? List.of() : List.of(proxy));
        }

        public static ProviderResult successList(String message, List<ProxyEntry> proxies)
        {
            return new ProviderResult(true, message, null, List.copyOf(proxies));
        }

        public static ProviderResult failure(String message)
        {
            return new ProviderResult(false, message, null, List.of());
        }
    }

    public record ValidationResult(boolean success, String message, long latencyMillis)
    {
        public static ValidationResult success(long latencyMillis)
        {
            return new ValidationResult(true, "Validation successful", latencyMillis);
        }

        public static ValidationResult failure(String message)
        {
            return new ValidationResult(false, message, -1L);
        }
    }

    public record RouteDecision(ProxyEntry proxy, ScopeRule scopeRule)
    {
    }
}
