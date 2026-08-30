package io.opendoggo.hook;

import io.opendoggo.model.ContentBlock;

/**
 * PreToolUse hook：每次工具执行前触发。
 *
 * 决策协议：返回 null 表示放行；
 * 返回非 null 为拦截原因，直接成为
 * 带原 tool_use_id 的 tool_result 内容，
 * 该次调用被跳过，同轮其他调用照常执行。
 */
@FunctionalInterface
public interface PreToolUseHook {

    String beforeToolUse(ContentBlock toolCall);
}
