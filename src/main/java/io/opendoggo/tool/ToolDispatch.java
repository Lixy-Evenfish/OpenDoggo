package io.opendoggo.tool;

import java.util.HashMap;
import java.util.Map;


import io.opendoggo.model.ContentBlock;

public class ToolDispatch {
    private final Map<String,ToolHandler> handlers = new HashMap<>();
    
    public void register(String name,ToolHandler handler){
        handlers.put(name, handler);
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
}