package io.opendoggo.tool.impl;

import java.nio.file.Path;

/**
 * s02/s03 文件工具共用的路径解析：
 * 相对工作区解析并归一化，空路径返回 null（工具据此报错）。
 * s03 起工作区限制上移到权限层（PermissionChecker 闸门 2），
 * 用户批准后工具可以访问工作区外路径。
 */
final class WorkspacePaths {

    private WorkspacePaths() {
    }

    static Path resolveAny(Path workdir, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        return workdir
                .resolve(raw.strip())
                .normalize();
    }
}
