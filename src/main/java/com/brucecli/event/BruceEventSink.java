package com.brucecli.event;

@FunctionalInterface
public interface BruceEventSink {
    BruceEventSink NO_OP = event -> {
    };

    void emit(BruceEvent event);
}
