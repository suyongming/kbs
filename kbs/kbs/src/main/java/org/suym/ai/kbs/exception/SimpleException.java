package org.suym.ai.kbs.exception;


/**
 * - 简单版自定义异常
 *
 * @author zJun
 * @date 2020年10月12日 下午4:17:45
 */
public class SimpleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private Integer code;
    private String msg;
    private Object data;

    public SimpleException() {
        this.code = 50000;
    }

    public SimpleException(String msg) {
        this(50000, msg, null);
    }

    public SimpleException(Integer code) {
        this.code = code;
    }

    public SimpleException(Integer code, String msg) {
        this(code, msg, null);
    }

    public SimpleException(Integer code, String msg, Object data) {
        super(msg);
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static SimpleException getInstance() {
        return new SimpleException();
    }

    public static SimpleException getInstance(String message) {
        return new SimpleException(message);
    }

    public static SimpleException getInstance(Integer code, String message) {
        return getInstance(code, message, null);
    }

    public static SimpleException getInstance(Integer code, String message, Object data) {
        return new SimpleException(code, message, data);
    }

    public static SimpleException getInstance(Integer code) {
        return getInstance(code, null);
    }

    public static SimpleException getInstance(Integer code, Object data) {
        return getInstance(code, null, data);
    }

    public static SimpleException getInstance(IResStatus status) {
        return getInstance(status, status.getMessage());
    }

    public static SimpleException getInstance(IResStatus status, Object data) {
        return getInstance(status.getCode(), status.getMessage(), data);
    }

    public static SimpleException getInstance(IResStatus status, String msg) {
        return getInstance(status, msg, null);
    }

    public static SimpleException getInstance(IResStatus status, String msg, Object data) {
        return getInstance(status.getCode(), msg, data);
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}