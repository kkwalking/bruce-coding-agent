package com.brucecli.event;

@FunctionalInterface
public interface BruceEventListener {
    void onEvent(BruceEvent event);
}
