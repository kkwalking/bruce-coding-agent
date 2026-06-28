package com.brucecli.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class BruceEventBus implements BruceEventSink {
    private static final Logger logger = LoggerFactory.getLogger(BruceEventBus.class);

    private final List<BruceEventListener> listeners = new CopyOnWriteArrayList<>();

    public Runnable subscribe(BruceEventListener listener) {
        if (listener == null) {
            return () -> {
            };
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void emit(BruceEvent event) {
        if (event == null) {
            return;
        }
        for (BruceEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException exception) {
                logger.warn("Bruce event listener failed for {}: {}", event.type(), exception.getMessage());
                logger.debug("Bruce event listener failure", exception);
            }
        }
    }
}
