package com.brucecli.event;

import java.time.Instant;

public interface BruceEvent {
    String type();

    String runId();

    Instant timestamp();
}
