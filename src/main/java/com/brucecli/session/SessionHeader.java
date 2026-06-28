package com.brucecli.session;

public record SessionHeader(
    String type,
    int version,
    String id,
    String createdAt,
    String cwd,
    String name
) {
}
