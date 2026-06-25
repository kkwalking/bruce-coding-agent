package com.brucecli.rag.model;

@FunctionalInterface
public interface IndexProgressListener {
    IndexProgressListener NOOP = progress -> {
    };

    void onProgress(IndexProgress progress);

    static IndexProgressListener noop() {
        return NOOP;
    }
}
