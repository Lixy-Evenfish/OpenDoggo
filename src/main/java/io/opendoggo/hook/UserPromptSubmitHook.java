package io.opendoggo.hook;

/**
 * UserPromptSubmit hook：用户输入提交后、
 * 进入历史和 LLM 之前触发。
 *
 * 返回值不参与控制流（fire-and-forget），
 * 适合输入日志、上下文注入等旁观用途。
 */
@FunctionalInterface
public interface UserPromptSubmitHook {

    void onSubmit(String query);
}
