package io.opendoggo.model;

import java.io.IOException;
import java.util.List;

/**
 * 模型客户端抽象。
 *
 * AgentLoop 只依赖该接口，不关心具体网关。
 */
public interface ModelClient {

    ModelResponse createMessage(
            List<Message> messages
    ) throws IOException, InterruptedException;
}