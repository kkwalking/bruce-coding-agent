package com.brucecli.session.compaction;

public record CompactionResult(
    String summary,
    String firstKeptEntryId,
    int tokensBefore,
    int estimatedTokensAfter,
    CompactionDetails details
) {
}
