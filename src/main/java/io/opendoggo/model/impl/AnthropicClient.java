package io.opendoggo.model.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opendoggo.model.ContentBlock;
import io.opendoggo.model.Message;
import io.opendoggo.model.ModelClient;
import io.opendoggo.model.ModelResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 基于 Anthropic Messages API 的 ModelClient 实现。
 *
 * 对应 s1 中的 client.messages.create(...)。
 * 只使用 JDK 自带的 HttpClient，不引入 SDK 依赖。
 */
public final class AnthropicClient implements ModelClient {

    public static final String DEFAULT_BASE_URL =
            "https://api.anthropic.com";

    private static final String API_VERSION = "2023-06-01";

    private static final int MAX_TOKENS = 8000;

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    /**
     * 工具定义：与 s1 的 TOOLS 保持一致，只有 bash。
     */
    private final URI endpoint;
    private final String apiKey;
    private final String modelId;
    private final String systemPrompt;
    private final HttpClient httpClient;
    private JsonNode toolDefinitions;

    public AnthropicClient(
            String baseUrl,
            String apiKey,
            String modelId,
            String systemPrompt,
            JsonNode toolDefinitions
    ) {
        Objects.requireNonNull(baseUrl, "baseUrl cannot be null");
        Objects.requireNonNull(apiKey, "apiKey cannot be null");
        Objects.requireNonNull(modelId, "modelId cannot be null");
        Objects.requireNonNull(
                systemPrompt,
                "systemPrompt cannot be null"
        );
        Objects.requireNonNull(
                toolDefinitions,
                "toolDefinitions cannot be null"
        );

        this.endpoint = URI.create(
                stripTrailingSlash(baseUrl) + "/v1/messages"
        );

        this.apiKey = apiKey;
        this.modelId = modelId;
        this.systemPrompt = systemPrompt;
        this.toolDefinitions = toolDefinitions;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public ModelResponse createMessage(
            List<Message> messages
    ) throws IOException, InterruptedException {

        Objects.requireNonNull(
                messages,
                "messages cannot be null"
        );

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(300))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(
                                buildRequestBody(messages)
                        ),
                        StandardCharsets.UTF_8
                ))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(
                        StandardCharsets.UTF_8
                )
        );

        if (response.statusCode() / 100 != 2) {
            throw new IOException(
                    "Model request failed with HTTP "
                            + response.statusCode()
                            + ": "
                            + abbreviate(response.body(), 2000)
            );
        }

        return parseResponse(response.body());
    }

    /**
     * 组装请求体，字段与 s1 一致。
     */
    private ObjectNode buildRequestBody(
            List<Message> messages
    ) {
        ObjectNode body = MAPPER.createObjectNode();

        body.put("model", modelId);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", systemPrompt);
        body.set("tools", toolDefinitions);

        ArrayNode messageArray = body.putArray("messages");

        for (Message message : messages) {
            ObjectNode node = messageArray.addObject();
            node.put("role", message.role());
            node.set("content", message.content());
        }

        return body;
    }

    /**
     * HTTP 响应当成 JSON 树解析，只抽出 text / tool_use。
     *
     * body: String → root: JsonNode → blocks: ContentBlock 列表
     * → ModelResponse。
     */
    private ModelResponse parseResponse(String body)
            throws IOException {

        // String → JsonNode。整段 HTTP body 变成可按下标/字段走的树。
        JsonNode root = MAPPER.readTree(body);

        // 业务错误在 JSON 里，不在 HTTP 状态码上。
        JsonNode error = root.get("error");

        if (error != null && !error.isNull()) {
            throw new IOException(
                    "Model returned an error: " + error
            );
        }

        List<ContentBlock> blocks = new ArrayList<>();

        // content 是数组；缺字段时 path() 给 missing node，for 直接跳过。
        // 每个元素仍是 JsonNode，按 type 决定要不要变成 ContentBlock。
        for (JsonNode node : root.path("content")) {
            String type = node.path("type").asText("");

            if ("text".equals(type)) {
                // 普通回复文字。asText("")：没有 text 就当空串。
                blocks.add(ContentBlock.text(
                        node.path("text").asText("")
                ));

            } else if ("tool_use".equals(type)) {
                // 模型要调工具。id 用来把结果对回去，name/input 是调用参数。
                JsonNode input = node.get("input");

                blocks.add(ContentBlock.toolUse(
                        node.path("id").asText(),
                        node.path("name").asText(),
                        // input 缺失时给空对象，避免后面取 command 空指针。
                        input == null || input.isNull()
                                ? MAPPER.createObjectNode()
                                : input
                ));
            }
            // thinking 等其它 type 直接丢掉。
        }

        // JsonNode 列表到此结束，往上只认 ContentBlock。
        return new ModelResponse(blocks);
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = value.strip();

        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(
                    0,
                    trimmed.length() - 1
            );
        }

        return trimmed;
    }

    private static String abbreviate(
            String value,
            int maximumLength
    ) {
        if (value == null) {
            return "";
        }

        if (value.length() <= maximumLength) {
            return value;
        }

        return value.substring(0, maximumLength) + "...";
    }
}
