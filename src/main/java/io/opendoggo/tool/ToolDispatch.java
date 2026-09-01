package io.opendoggo.tool;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opendoggo.model.ContentBlock;

public class ToolDispatch {
    private final Map<String,ToolHandler> handlers = new HashMap<>();
    
    public void register(ToolHandler handler){
        handlers.put(handler.name(), handler);
    }

    /** s14：注册名查询——MCP 前缀撞名检测用。 */
    public boolean hasTool(String name) {
        return handlers.containsKey(name);
    }

    public String execute(ContentBlock toolCall){
        if (toolCall == null) {
            return "Error: Empty tool call";
        }
        String name = toolCall.name();
        ToolHandler handler = handlers.get(name);

        if(handler == null){
            return "Error: UnKnown tool " + name;
        }
        else{
            return handler.execute(toolCall.input());
        }
    }

    public ArrayNode toolDefinitions() {
        ArrayNode tools =
                JsonNodeFactory.instance.arrayNode();

        for (ToolHandler handler
                : handlers.values()) {
            ObjectNode tool = tools.addObject();
            tool.put("name", handler.name());
            tool.put(
                    "description",
                    handler.description()
            );
            tool.set(
                    "input_schema",
                    handler.inputSchema()
            );
        }

        return tools;
    }
}