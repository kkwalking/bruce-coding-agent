package com.brucecli.web.search;

import java.io.IOException;
import java.util.List;

/**
 * 搜索引擎 Provider 抽象。
 *
 * <p>Agent 只关心统一的搜索结果，不需要知道背后是智谱、SerpAPI 还是 SearXNG。</p>
 */
public interface SearchProvider {
    String name();

    boolean isReady();

    String unavailableHint();

    List<WebSearchResult> search(String query, int topK) throws IOException;
}
