package org.suym.ai.kbs.exception;

/**
 * - 状态码
 *
 * @author zJun
 * @date 2020年9月21日 下午6:38:16
 */
public enum ResStatus implements IResStatus {

    /**
     * - 访问成功
     */
    OK(0, "OK"),
    /**
     * - 参数错误
     */
    BAD_REQUEST(40000, "参数错误"),
    /**
     * - 未授权或登录已过期
     */
    UNAUTHORIZED(40001, "未授权或登录已过期"),
    /**
     * - 权限不足
     */
    NO_POWER(40002, "权限不足"),
    /**
     * - 账号密码错误
     */
    PASSWORD_ERROR(40003, "账号密码错误"),
    /**
     * - 无效的令牌
     */
    INVALID_TOKEN(40004, "无效的令牌"),
    /**
     * - 无效的令牌
     */
    DISABLE_USER(40005, "用户被禁用"),
    /**
     * - 未注册
     */
    UN_REGISTER(50001, "未注册"),
    /**
     * - 非正常请求
     */
    ILLEGAL(50002, "非正常请求"),
    /**
     * - 服务重启中，请稍后
     */
    SERVICE_RESTART(50003, "服务重启中，请稍后"),
    /**
     * - 接口不符合开发规范
     */
    NO_STANDARD(50004, "接口不符合开发规范"),
    /**
     * - NULL异常。抛出该异常时请重写msg
     */
    NULL_ERROR(50005, "NULL异常"),
    /**
     * - 不符合条件的异常
     */
    INELIGIBLE_ERROR(50006, "不符合条件"),
    /**
     * 请求过于频繁，请稍后重试
     */
    TOO_MANY_REQUESTS(50009, "请求过于频繁，请稍后重试"),

    /**
     * 重复请求，请稍后重试
     */
    REPEATED_REQUESTS(50010, "重复请求，请稍后重试");

    private final int code;
    private final String message;

    ResStatus(int code, String msg) {
        this.code = code;
        this.message = msg;
    }

    @Override
    public int getCode() {
        return this.code;
    }

    @Override
    public String getMessage() {
        return this.message;
    }
}