package com.brucecli.web.search;

import com.brucecli.config.BruceSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchProviderFactoryTest {
    @Test
    void picksProviderFromExplicitOrAvailableConfig() {
        assertEquals("serpapi", SearchProviderFactory.pickProvider("serpapi", "glm", "", ""));
        assertEquals("zhipu", SearchProviderFactory.pickProvider("", "glm-key", "", ""));
        assertEquals("serpapi", SearchProviderFactory.pickProvider("", "", "serp-key", ""));
        assertEquals("searxng", SearchProviderFactory.pickProvider("", "", "", "http://localhost:8888"));
        assertEquals("zhipu", SearchProviderFactory.pickProvider("", "", "", ""));
    }

    @Test
    void zhipuProviderReportsSettingsPathWhenMissingApiKey() {
        WebSearchConfig config = WebSearchConfig.empty();
        SearchProvider provider = SearchProviderFactory.create(config);

        assertEquals("zhipu", provider.name());
        assertFalse(provider.isReady());
        assertTrue(provider.unavailableHint().contains("webSearch.zhipu.apiKey"));
    }

    @Test
    void createsProviderFromWebSearchSettings() {
        BruceSettings.WebSearchSettings settings = new BruceSettings.WebSearchSettings();
        settings.setProvider("searxng");
        settings.getZhipu().setApiKey("zhipu-key");
        settings.getSearxng().setUrl("http://localhost:8888");

        SearchProvider provider = SearchProviderFactory.create(WebSearchConfig.fromSettings(settings));

        assertEquals("searxng", provider.name());
        assertTrue(provider.isReady());
    }
}
