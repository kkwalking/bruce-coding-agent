package com.brucecli.session.compaction;

import java.util.List;

public record CompactionDetails(
    List<String> readFiles,
    List<String> modifiedFiles
) {
    public CompactionDetails {
        readFiles = readFiles == null ? List.of() : List.copyOf(readFiles);
        modifiedFiles = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
    }
}
