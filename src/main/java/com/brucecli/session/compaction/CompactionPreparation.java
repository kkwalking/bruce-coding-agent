package com.brucecli.session.compaction;

import com.brucecli.llm.Message;

import java.util.List;

public record CompactionPreparation(
    String firstKeptEntryId,
    List<Message> messagesToSummarize,
    List<Message> turnPrefixMessages,
    boolean splitTurn,
    int tokensBefore,
    String previousSummary,
    CompactionDetails details,
    int reserveTokens
) {
    public CompactionPreparation {
        messagesToSummarize = messagesToSummarize == null ? List.of() : List.copyOf(messagesToSummarize);
        turnPrefixMessages = turnPrefixMessages == null ? List.of() : List.copyOf(turnPrefixMessages);
        previousSummary = previousSummary == null ? "" : previousSummary;
        details = details == null ? new CompactionDetails(List.of(), List.of()) : details;
        reserveTokens = Math.max(1, reserveTokens);
    }
}
