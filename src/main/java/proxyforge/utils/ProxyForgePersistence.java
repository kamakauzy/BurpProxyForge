package proxyforge.utils;

import burp.api.montoya.persistence.PersistedObject;
import proxyforge.models.ProxyForgeModels;
import proxyforge.models.ProxyForgeModels.ExtensionState;
import proxyforge.models.ProxyForgeModels.ProviderFormState;
import proxyforge.models.ProxyForgeModels.ProxyEntry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ProxyForgePersistence
{
    private static final String STATE_KEY = "proxyforge.state";

    private final PersistedObject store;
    private final ProxyForgeLogger logger;

    public ProxyForgePersistence(PersistedObject store, ProxyForgeLogger logger)
    {
        this.store = store;
        this.logger = logger;
    }

    public ExtensionState load()
    {
        String json = store.getString(STATE_KEY);
        if (json == null || json.isBlank())
        {
            return new ExtensionState();
        }

        json = sanitizeLegacyState(json);

        try
        {
            ExtensionState state = ProxyForgeJson.read(json, ExtensionState.class);
            if (state.settings == null)
            {
                state.settings = new ProxyForgeModels.ExtensionSettings();
            }
            if (state.proxies == null)
            {
                state.proxies = new java.util.ArrayList<>();
            }
            if (state.scopeRules == null)
            {
                state.scopeRules = new java.util.ArrayList<>();
            }
            if (state.stickyAssignments == null)
            {
                state.stickyAssignments = new LinkedHashMap<>();
            }
            if (state.providerFormStates == null)
            {
                state.providerFormStates = new java.util.EnumMap<>(ProxyForgeModels.ProviderType.class);
            }
            return state;
        }
        catch (RuntimeException exception)
        {
            logger.error("Unable to load persisted ProxyForge state, starting fresh.", exception);
            return new ExtensionState();
        }
    }

    public void save(ExtensionState state)
    {
        ExtensionState snapshot = ProxyForgeJson.read(ProxyForgeJson.write(state), ExtensionState.class);
        if (!snapshot.settings.persistSensitiveFields)
        {
            stripSensitiveData(snapshot);
        }
        store.setString(STATE_KEY, ProxyForgeJson.write(snapshot));
    }

    private void stripSensitiveData(ExtensionState state)
    {
        for (ProxyEntry proxy : state.proxies)
        {
            proxy.username = "";
            proxy.password = "";
        }

        for (ProviderFormState formState : state.providerFormStates.values())
        {
            Map<String, String> scrubbed = new LinkedHashMap<>();
            formState.fields.forEach((key, value) ->
            {
                if (isSensitiveKey(key))
                {
                    scrubbed.put(key, "");
                }
                else
                {
                    scrubbed.put(key, value);
                }
            });
            formState.fields = scrubbed;
        }
    }

    private boolean isSensitiveKey(String key)
    {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("secret")
            || normalized.contains("token")
            || normalized.contains("password")
            || normalized.endsWith("key")
            || normalized.contains("accesskey");
    }

    private String sanitizeLegacyState(String json)
    {
        return json.replace("\"MOCK\"", "\"ACTIVE\"");
    }
}
