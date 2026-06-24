# 待确认问题

## DeepSeek usage 中的 cache hit 统计解析

当前 `OpenAiCompatibleChatClient#parseUsage()` 已经能读取：

- `prompt_tokens`
- `completion_tokens`
- `cached_input_tokens`
- `prompt_tokens_details.cached_tokens`

但 DeepSeek Chat Completion 的 usage 使用的是：

- `prompt_cache_hit_tokens`
- `prompt_cache_miss_tokens`

因此当前 `ChatResponse.cachedInputTokens()` 在 DeepSeek 响应下可能会被解析为 `0`。

先不在本轮实现中修改。后续决定做 token 统计、成本统计或 prompt cache 展示时，再补充 DeepSeek 的 `prompt_cache_hit_tokens` 解析，并考虑是否把 `prompt_cache_miss_tokens`、`reasoning_tokens` 一并纳入响应模型。
