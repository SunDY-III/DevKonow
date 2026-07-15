package com.devknow.common;

/**
 * Git 操作异常。
 * 携带错误码供前端展示对应的用户提示和操作选项。
 */
public class GitException extends BizException {

    public enum ErrorCode {
        NETWORK_ERROR,          // 网络不通 → 显示重试按钮
        REPO_NOT_FOUND,         // 仓库不存在
        PERMISSION_DENIED,      // 无权限 → 提示配置 SSH Key
        AUTH_REQUIRED,          // 私有仓库需要认证（TODO）
        REPO_TOO_LARGE,         // 仓库过大（TODO）
        CLONE_FAILED,           // 克隆失败（其他原因）
        PARSE_FAILED            // 解析失败
    }

    private final ErrorCode errorCode;

    public GitException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
