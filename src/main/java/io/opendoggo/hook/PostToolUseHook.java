package io.opendoggo.hook;

import io.opendoggo.model.ContentBlock;

/**
 * PostToolUse hook：工具执行后、结果入历史前触发。
 *
 * 返回值不参与控制流，适合副作用检查、
 * 大输出警告等旁观用途。
 */
@FunctionalInterface
public interface PostToolUseHook {

    void afterToolUse(ContentBlock toolCall, String output);
}
