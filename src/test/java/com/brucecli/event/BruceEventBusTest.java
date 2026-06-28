package com.brucecli.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BruceEventBusTest {
    @Test
    void emitsToListenersInSubscriptionOrder() {
        BruceEventBus bus = new BruceEventBus();
        List<String> received = new ArrayList<>();

        bus.subscribe(event -> received.add("first:" + event.type()));
        bus.subscribe(event -> received.add("second:" + event.type()));

        bus.emit(new BruceEvents.Activity("run", "working"));

        assertEquals(List.of("first:activity", "second:activity"), received);
    }

    @Test
    void unsubscribeStopsDelivery() {
        BruceEventBus bus = new BruceEventBus();
        List<String> received = new ArrayList<>();
        Runnable unsubscribe = bus.subscribe(event -> received.add(event.type()));

        unsubscribe.run();
        bus.emit(new BruceEvents.Activity("run", "working"));

        assertEquals(List.of(), received);
    }

    @Test
    void listenerFailureDoesNotBlockOtherListeners() {
        BruceEventBus bus = new BruceEventBus();
        List<String> received = new ArrayList<>();

        bus.subscribe(event -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(event -> received.add(event.type()));

        bus.emit(new BruceEvents.Activity("run", "working"));

        assertEquals(List.of("activity"), received);
    }
}
