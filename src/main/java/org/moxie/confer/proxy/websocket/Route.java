package org.moxie.confer.proxy.websocket;

import java.util.Objects;

public record Route(String verb, String path) {

    public Route {
        Objects.requireNonNull(verb, "verb cannot be null");
        Objects.requireNonNull(path, "path cannot be null");
    }
}