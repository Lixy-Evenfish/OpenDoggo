package io.opendoggo.model;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


public record ModelResponse(
        List<ContentBlock> content
) {

    public ModelResponse {
        Objects.requireNonNull(
                content,
                "content cannot be null"
        );

        content = List.copyOf(content);
    }

    public List<ContentBlock> toolCalls() {
        return content.stream()
                .filter(ContentBlock::isToolUse)
                .toList();
    }

    public String text() {
        return content.stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::text)
                .filter(text ->
                        text != null && !text.isBlank()
                )
                .collect(Collectors.joining(
                        System.lineSeparator()
                ));
    }
}