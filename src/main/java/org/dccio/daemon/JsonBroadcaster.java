package org.dccio.daemon;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface JsonBroadcaster {
    void broadcast(JsonObject message);
}

