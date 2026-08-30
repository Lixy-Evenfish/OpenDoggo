package io.opendoggo.tool.impl;

import java.nio.file.Path;

/**
 * s02 文件工具共用的路径解析：
 * 相对工作区解析并归一化，逸出工作区时返回 null（工具据此拒绝）。
 * s03 会把这道限制移到权限规则层。
 */
final class WorkspacePaths {

    private WorkspacePaths() {
    }

    static Path resolveIn(Path workdir, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        Path resolved = workdir
                .resolve(raw.strip())
                .normalize();

        return resolved.startsWith(workdir)
                ? resolved
                : null;
    }
}
