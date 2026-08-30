package io.opendoggo.hook;

import io.opendoggo.model.Message;

import java.util.List;

/**
 * Stop hook：模型不再调用工具、
 * 循环即将退出时触发。
 *
 * 决策协议：返回 null 允许退出；
 * 返回非 null 则作为一条 user 消息注入历史，
 * 循环继续而不是退出。
 */
@FunctionalInterface
public interface StopHook {

    String onStop(List<Message> messages);
}
