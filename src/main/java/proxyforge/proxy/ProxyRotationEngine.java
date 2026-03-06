package proxyforge.proxy;

import proxyforge.models.ProxyForgeModels;
import proxyforge.models.ProxyForgeModels.ExtensionState;
import proxyforge.models.ProxyForgeModels.ProxyEntry;
import proxyforge.models.ProxyForgeModels.ProxyStatus;
import proxyforge.models.ProxyForgeModels.RouteDecision;
import proxyforge.models.ProxyForgeModels.ScopeRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProxyRotationEngine
{
    private final ExtensionState state;
    private final AtomicInteger roundRobinIndex = new AtomicInteger();
    private final Random random = new Random();
    private final Map<String, String> stickyAssignments = new ConcurrentHashMap<>();

    public ProxyRotationEngine(ExtensionState state)
    {
        this.state = Objects.requireNonNull(state, "state");
        this.stickyAssignments.putAll(state.stickyAssignments);
    }

    public synchronized RouteDecision chooseProxy(String host)
    {
        return chooseProxy(host, false);
    }

    public synchronized RouteDecision chooseProxy(String host, boolean requiresConnect)
    {
        List<ProxyEntry> candidates = activeProxies(requiresConnect);
        if (candidates.isEmpty())
        {
            return new RouteDecision(null, null);
        }

        ScopeRule matchedScope = state.scopeRules.stream()
            .filter(rule -> rule.matches(host))
            .findFirst()
            .orElse(null);

        if (matchedScope != null)
        {
            ProxyEntry scopedProxy = resolveScopeProxy(matchedScope, candidates);
            if (scopedProxy != null)
            {
                stickyAssignments.put(host, scopedProxy.id);
                persistStickyAssignments();
                return new RouteDecision(scopedProxy, matchedScope);
            }
        }

        ProxyEntry selected = switch (state.settings.rotationStrategy)
        {
            case RANDOM -> candidates.get(random.nextInt(candidates.size()));
            case ROUND_ROBIN -> candidates.get(Math.floorMod(roundRobinIndex.getAndIncrement(), candidates.size()));
            case LEAST_USED -> candidates.stream().min(Comparator.comparingLong(proxy -> proxy.requestsServed)).orElse(candidates.getFirst());
            case STICKY_PER_HOST -> stickyProxy(host, candidates);
            case PER_SCOPE_RULE -> matchedScope == null
                ? candidates.get(Math.floorMod(roundRobinIndex.getAndIncrement(), candidates.size()))
                : resolveScopeProxy(matchedScope, candidates);
        };

        if (selected != null && host != null && !host.isBlank())
        {
            stickyAssignments.put(host, selected.id);
            persistStickyAssignments();
        }
        return new RouteDecision(selected, matchedScope);
    }

    public synchronized ProxyEntry rotateNow()
    {
        List<ProxyEntry> candidates = activeProxies();
        if (candidates.isEmpty())
        {
            return null;
        }
        stickyAssignments.clear();
        persistStickyAssignments();
        return candidates.get(Math.floorMod(roundRobinIndex.incrementAndGet(), candidates.size()));
    }

    public synchronized List<ProxyEntry> activeProxies()
    {
        return activeProxies(false);
    }

    public synchronized List<ProxyEntry> activeProxies(boolean requiresConnect)
    {
        List<ProxyEntry> candidates = new ArrayList<>();
        for (ProxyEntry proxy : state.proxies)
        {
            if (proxy.enabled && proxy.status == ProxyStatus.ACTIVE)
            {
                if (!requiresConnect || proxy.supportsConnect())
                {
                    candidates.add(proxy);
                }
            }
        }
        return candidates;
    }

    public synchronized void recordSuccess(ProxyEntry proxyEntry)
    {
        if (proxyEntry != null)
        {
            proxyEntry.markRequestSuccess();
        }
    }

    public synchronized void recordFailure(ProxyEntry proxyEntry, String message)
    {
        if (proxyEntry != null)
        {
            proxyEntry.markRequestFailure(message);
        }
    }

    private ProxyEntry stickyProxy(String host, List<ProxyEntry> candidates)
    {
        if (host == null || host.isBlank())
        {
            return candidates.get(Math.floorMod(roundRobinIndex.getAndIncrement(), candidates.size()));
        }

        String assignedId = stickyAssignments.get(host);
        if (assignedId != null)
        {
            Optional<ProxyEntry> current = candidates.stream().filter(proxy -> assignedId.equals(proxy.id)).findFirst();
            if (current.isPresent())
            {
                return current.get();
            }
        }

        ProxyEntry selected = candidates.get(Math.floorMod(roundRobinIndex.getAndIncrement(), candidates.size()));
        stickyAssignments.put(host, selected.id);
        persistStickyAssignments();
        return selected;
    }

    private ProxyEntry resolveScopeProxy(ScopeRule rule, List<ProxyEntry> candidates)
    {
        if (rule.assignedProxyId != null)
        {
            return candidates.stream()
                .filter(proxy -> rule.assignedProxyId.equals(proxy.id))
                .findFirst()
                .orElse(null);
        }

        if (rule.preferredProvider != null)
        {
            return candidates.stream()
                .filter(proxy -> proxy.providerType == rule.preferredProvider)
                .findFirst()
                .orElse(null);
        }

        return null;
    }

    private void persistStickyAssignments()
    {
        state.stickyAssignments.clear();
        state.stickyAssignments.putAll(stickyAssignments);
    }
}
