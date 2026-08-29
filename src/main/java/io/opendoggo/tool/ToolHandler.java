package io.opendoggo.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface ToolHandler {
    String execute(JsonNode input);

    String name();

    String description();

    JsonNode inputSchema();

}
