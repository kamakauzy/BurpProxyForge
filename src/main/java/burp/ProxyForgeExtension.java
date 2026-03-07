package burp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import proxyforge.models.ProxyForgeModels;
import proxyforge.models.ProxyForgeModels.DeployRequest;
import proxyforge.models.ProxyForgeModels.ExtensionState;
import proxyforge.models.ProxyForgeModels.ProviderFormState;
import proxyforge.models.ProxyForgeModels.ProviderResult;
import proxyforge.models.ProxyForgeModels.ProviderType;
import proxyforge.models.ProxyForgeModels.ProxyEntry;
import proxyforge.models.ProxyForgeModels.RotationStrategy;
import proxyforge.models.ProxyForgeModels.ScopeRule;
import proxyforge.providers.ProviderRegistry;
import proxyforge.proxy.ForwarderRewriteHttpHandler;
import proxyforge.proxy.LocalProxyServer;
import proxyforge.proxy.ProxyRotationEngine;
import proxyforge.ui.ProxyForgeTab;
import proxyforge.utils.BurpUpstreamProxyManager;
import proxyforge.utils.ProxyForgeHttp;
import proxyforge.utils.ProxyForgeJson;
import proxyforge.utils.ProxyForgeLogger;
import proxyforge.utils.ProxyForgePersistence;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProxyForgeExtension implements BurpExtension
{
    private MontoyaApi api;
    private ProxyForgeLogger logger;
    private ProxyForgePersistence persistence;
    private ExtensionState state;
    private ProxyRotationEngine rotationEngine;
    private ProviderRegistry providerRegistry;
    private BurpUpstreamProxyManager burpUpstreamProxyManager;
    private LocalProxyServer localProxyServer;
    private ProxyForgeTab tab;
    private ExecutorService executorService;
    private final List<Registration> registrations = new ArrayList<>();

    @Override
    public void initialize(MontoyaApi api)
    {
        this.api = api;
        this.logger = new ProxyForgeLogger(api.logging());
        this.persistence = new ProxyForgePersistence(api.persistence().extensionData(), logger);
        this.state = persistence.load();
        this.rotationEngine = new ProxyRotationEngine(state);
        this.providerRegistry = new ProviderRegistry(logger);
        this.burpUpstreamProxyManager = new BurpUpstreamProxyManager(api.burpSuite(), logger);
        this.localProxyServer = new LocalProxyServer(rotationEngine, logger, this::persistAndRefresh);
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();

        api.extension().setName("ProxyForge");
        registrations.add(api.extension().registerUnloadingHandler(this::shutdown));

        this.tab = new ProxyForgeTab(new Actions(), logger);
        api.userInterface().applyThemeToComponent(tab);
        registrations.add(api.userInterface().registerSuiteTab("ProxyForge", tab));
        registrations.add(api.http().registerHttpHandler(new ForwarderRewriteHttpHandler(rotationEngine, logger, this::persistAndRefresh)));

        if (state.settings.autoStartProxy)
        {
            startOrRestartProxy();
        }
        else
        {
            syncBurpUpstreamRule();
        }

        if (state.settings.firstLaunch)
        {
            SwingUtilities.invokeLater(tab::showQuickStartWizard);
        }

        logger.info("ProxyForge initialized successfully.");
    }

    private synchronized void shutdown()
    {
        try
        {
            disableBurpUpstreamRuleQuietly();
            if (localProxyServer != null)
            {
                localProxyServer.stop();
            }
        }
        catch (Exception exception)
        {
            logger.error("Error stopping local proxy server during unload", exception);
        }

        if (executorService != null)
        {
            executorService.shutdownNow();
        }

        persistState();
        registrations.forEach(Registration::deregister);
        registrations.clear();
    }

    private synchronized void persistState()
    {
        persistence.save(state);
    }

    private void persistAndRefresh()
    {
        persistState();
        if (tab != null)
        {
            SwingUtilities.invokeLater(tab::refresh);
        }
    }

    private synchronized void startOrRestartProxy()
    {
        try
        {
            localProxyServer.start(state.settings.localProxyPort, state.settings.allowExternalBind);
        }
        catch (IOException exception)
        {
            logger.error("Unable to start local ProxyForge listener", exception);
        }
        syncBurpUpstreamRule();
        persistAndRefresh();
    }

    private synchronized void syncBurpUpstreamRule()
    {
        try
        {
            boolean hasUpstreamProxy = !rotationEngine.activeUpstreamProxies(true).isEmpty();
            boolean enableRule = state.settings.manageBurpUpstreamProxy && localProxyServer.isRunning() && hasUpstreamProxy;
            burpUpstreamProxyManager.syncManagedRule(enableRule, state.settings.localProxyPort);
            if (state.settings.manageBurpUpstreamProxy && !hasUpstreamProxy)
            {
                logger.info("Burp upstream proxy rule left disabled because the current pool contains only forwarders.");
            }
        }
        catch (Exception exception)
        {
            logger.error("Unable to synchronize Burp upstream proxy rule", exception);
        }
    }

    private synchronized void disableBurpUpstreamRuleQuietly()
    {
        try
        {
            burpUpstreamProxyManager.syncManagedRule(false, state.settings.localProxyPort);
        }
        catch (Exception exception)
        {
            logger.error("Unable to disable Burp upstream proxy rule during cleanup", exception);
        }
    }

    private synchronized ExtensionState snapshot()
    {
        return ProxyForgeJson.read(ProxyForgeJson.write(state), ExtensionState.class);
    }

    private synchronized void mergeProxy(ProxyEntry candidate)
    {
        Objects.requireNonNull(candidate, "candidate");
        int existingIndex = -1;
        for (int index = 0; index < state.proxies.size(); index++)
        {
            ProxyEntry current = state.proxies.get(index);
            if (current.id.equals(candidate.id)
                || (!current.providerResourceId.isBlank() && current.providerResourceId.equals(candidate.providerResourceId)))
            {
                existingIndex = index;
                break;
            }
        }

        if (existingIndex >= 0)
        {
            ProxyEntry existing = state.proxies.get(existingIndex);
            state.proxies.set(existingIndex, mergeExistingProxy(existing, candidate));
        }
        else
        {
            state.proxies.add(candidate);
        }
        syncBurpUpstreamRule();
        persistAndRefresh();
    }

    private ProxyEntry mergeExistingProxy(ProxyEntry existing, ProxyEntry candidate)
    {
        candidate.id = existing.id;
        candidate.enabled = existing.enabled;
        candidate.createdAt = existing.createdAt;
        candidate.lastValidatedAt = candidate.lastValidatedAt != null ? candidate.lastValidatedAt : existing.lastValidatedAt;
        candidate.lastUsedAt = candidate.lastUsedAt != null ? candidate.lastUsedAt : existing.lastUsedAt;
        candidate.requestsServed += existing.requestsServed;
        candidate.successfulRequests += existing.successfulRequests;
        candidate.failedRequests += existing.failedRequests;
        if (candidate.lastError == null || candidate.lastError.isBlank())
        {
            candidate.lastError = existing.lastError;
        }
        if (candidate.forwarderBaseUrl == null || candidate.forwarderBaseUrl.isBlank())
        {
            candidate.forwarderBaseUrl = existing.forwarderBaseUrl;
        }
        if (candidate.targetBaseUrl == null || candidate.targetBaseUrl.isBlank())
        {
            candidate.targetBaseUrl = existing.targetBaseUrl;
        }
        if (candidate.endpointHost == null || candidate.endpointHost.isBlank())
        {
            candidate.endpointHost = existing.endpointHost;
        }
        if (candidate.endpointPort <= 0)
        {
            candidate.endpointPort = existing.endpointPort;
        }
        if (candidate.username == null || candidate.username.isBlank())
        {
            candidate.username = existing.username;
        }
        if (candidate.password == null || candidate.password.isBlank())
        {
            candidate.password = existing.password;
        }
        existing.metadata.forEach(candidate.metadata::putIfAbsent);
        return candidate;
    }

    private synchronized void removeProxy(String proxyId)
    {
        state.proxies.removeIf(proxy -> proxy.id.equals(proxyId));
        syncBurpUpstreamRule();
        persistAndRefresh();
    }

    private final class Actions implements ProxyForgeTab.ProxyForgeActions
    {
        @Override
        public ExtensionState currentState()
        {
            return snapshot();
        }

        @Override
        public ProviderResult deploy(ProviderType providerType, Map<String, String> fields)
        {
            updateProviderFormState(providerType, fields);
            return providerRegistry.deploy(new DeployRequest(providerType, fields));
        }

        @Override
        public ProviderResult list(ProviderType providerType, Map<String, String> fields)
        {
            updateProviderFormState(providerType, fields);
            return providerRegistry.list(providerType, fields);
        }

        @Override
        public ProviderResult deleteProxy(ProxyEntry proxyEntry, Map<String, String> fields)
        {
            updateProviderFormState(proxyEntry.providerType, fields);
            ProviderResult result = providerRegistry.delete(proxyEntry, fields);
            if (result.success())
            {
                removeProxy(proxyEntry.id);
            }
            return result;
        }

        @Override
        public void upsertProxy(ProxyEntry proxyEntry)
        {
            mergeProxy(proxyEntry);
        }

        @Override
        public synchronized void updateProviderFormState(ProviderType providerType, Map<String, String> fields)
        {
            ProviderFormState providerFormState = state.providerFormStates.computeIfAbsent(providerType, ignored -> new ProviderFormState());
            providerFormState.fields.clear();
            providerFormState.fields.putAll(fields);
            persistAndRefresh();
        }

        @Override
        public synchronized void updateSettings(
            int port,
            RotationStrategy rotationStrategy,
            boolean autoStart,
            boolean manageBurpUpstreamProxy,
            boolean persistSensitive,
            boolean allowExternalBind,
            boolean restartProxy)
        {
            state.settings.localProxyPort = port;
            state.settings.rotationStrategy = rotationStrategy;
            state.settings.autoStartProxy = autoStart;
            state.settings.manageBurpUpstreamProxy = manageBurpUpstreamProxy;
            state.settings.persistSensitiveFields = persistSensitive;
            state.settings.allowExternalBind = allowExternalBind;
            if (restartProxy)
            {
                startOrRestartProxy();
            }
            else
            {
                syncBurpUpstreamRule();
                persistAndRefresh();
            }
        }

        @Override
        public synchronized void stopProxy()
        {
            localProxyServer.stop();
            syncBurpUpstreamRule();
            persistAndRefresh();
        }

        @Override
        public void rotateNow()
        {
            executorService.submit(() ->
            {
                ProxyEntry proxyEntry = rotationEngine.rotateNow();
                if (proxyEntry != null)
                {
                    logger.info("Rotation forced to next candidate: " + proxyEntry.name);
                }
                persistAndRefresh();
            });
        }

        @Override
        public void validateAll()
        {
            executorService.submit(() ->
            {
                synchronized (ProxyForgeExtension.this)
                {
                    for (ProxyEntry proxy : state.proxies)
                    {
                        proxy.markValidation(true, "");
                        var result = ProxyForgeHttp.validateProxy(proxy, java.time.Duration.ofSeconds(8));
                        proxy.markValidation(result.success(), result.message());
                        logger.info(proxy.name + " validation: " + result.message());
                    }
                }
                persistAndRefresh();
            });
        }

        @Override
        public synchronized void addScopeRule(ScopeRule scopeRule)
        {
            state.scopeRules.add(scopeRule);
            persistAndRefresh();
        }

        @Override
        public synchronized void removeScopeRule(String id)
        {
            state.scopeRules.removeIf(scopeRule -> scopeRule.id.equals(id));
            persistAndRefresh();
        }

        @Override
        public synchronized void markFirstLaunchComplete()
        {
            state.settings.firstLaunch = false;
            persistAndRefresh();
        }

        @Override
        public synchronized boolean isProxyRunning()
        {
            return localProxyServer.isRunning();
        }
    }
}
