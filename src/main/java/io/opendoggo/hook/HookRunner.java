package io.opendoggo.hook;

import io.opendoggo.model.ContentBlock;
import io.opendoggo.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * s04 的 hook 注册表，对应参考实现的
 * HOOKS 字典 + register_hook + trigger_hooks。
 *
 * 四个事件各维护一个按注册顺序执行的回调列表：
 * 有决策权的事件（PreToolUse、Stop）在第一个
 * 非 null 返回处短路；无决策权的事件
 * （UserPromptSubmit、PostToolUse）逐个通知。
 */
public final class HookRunner {

    private final List<UserPromptSubmitHook> userPromptSubmitHooks =
            new ArrayList<>();
    private final List<PreToolUseHook> preToolUseHooks =
            new ArrayList<>();
    private final List<PostToolUseHook> postToolUseHooks =
            new ArrayList<>();
    private final List<StopHook> stopHooks =
            new ArrayList<>();

    public void registerUserPromptSubmit(
            UserPromptSubmitHook hook
    ) {
        userPromptSubmitHooks.add(requireHook(hook));
    }

    public void registerPreToolUse(PreToolUseHook hook) {
        preToolUseHooks.add(requireHook(hook));
    }

    public void registerPostToolUse(PostToolUseHook hook) {
        postToolUseHooks.add(requireHook(hook));
    }

    public void registerStop(StopHook hook) {
        stopHooks.add(requireHook(hook));
    }

    /**
     * UserPromptSubmit：逐个通知，返回值不参与控制流。
     */
    public void triggerUserPromptSubmit(String query) {
        for (UserPromptSubmitHook hook : userPromptSubmitHooks) {
            hook.onSubmit(query);
        }
    }

    /**
     * PreToolUse：按注册顺序执行，
     * 第一个非 null 结果短路返回（拦截原因），
     * 全部为 null 则返回 null（放行）。
     */
    public String triggerPreToolUse(ContentBlock toolCall) {
        for (PreToolUseHook hook : preToolUseHooks) {
            String blocked = hook.beforeToolUse(toolCall);
            if (blocked != null) {
                return blocked;
            }
        }

        return null;
    }

    /**
     * PostToolUse：逐个通知，返回值不参与控制流。
     */
    public void triggerPostToolUse(
            ContentBlock toolCall,
            String output
    ) {
        for (PostToolUseHook hook : postToolUseHooks) {
            hook.afterToolUse(toolCall, output);
        }
    }

    /**
     * Stop：按注册顺序执行，
     * 第一个非 null 结果短路返回（强制继续的消息），
     * 全部为 null 则返回 null（允许退出）。
     */
    public String triggerStop(List<Message> messages) {
        for (StopHook hook : stopHooks) {
            String force = hook.onStop(messages);
            if (force != null) {
                return force;
            }
        }

        return null;
    }

    private static <T> T requireHook(T hook) {
        return Objects.requireNonNull(
                hook,
                "hook cannot be null"
        );
    }
}
