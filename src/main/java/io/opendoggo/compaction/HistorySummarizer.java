package io.opendoggo.compaction;

import java.io.IOException;

/**
 * s08 需求2：历史摘要的调用抽象。
 *
 * 输入是整段对话的 JSON 文本，输出是纯文本摘要。
 * 生产实现是 AnthropicClient.summarize——
 * 摘要专用 client 实例（system 固化为
 * "只整理事实"的摘要提示词、无 tools、
 * max_tokens=2000）；demo / 测试用 lambda 脚本替代。
 *
 * 不放进 ModelClient——脚本 client（TodoDemo 等）
 * 不应被迫实现一个自己用不到的能力，
 * 压缩器也只依赖这一个动作。
 */
@FunctionalInterface
public interface HistorySummarizer {

    String summarize(String conversationJson)
            throws IOException, InterruptedException;
}
