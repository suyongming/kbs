package org.suym.ai.kbs.dto.base;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.suym.ai.kbs.exception.IResStatus;
import org.suym.ai.kbs.exception.ResStatus;
import org.suym.ai.kbs.exception.SimpleException;

import java.io.Serializable;

/**
 * - ajax返回数据格式
 *
 * @param <T>
 * @author zJun
 * @date 2018年12月25日 上午11:25:55
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class JsonResult<T> implements Serializable {

    private static final long serialVersionUID = 7880326982542148254L;
    /**
     * 状态码
     */
    private Integer code;
    /**
     * 提示信息
     */
    private String msg;
    /**
     * 数据对象
     */
    private T data;

    /**
     * - 返回成功状态
     *
     * @return
     * @author zJun
     * @date 2018年12月25日 下午2:02:30
     */
    public static <T> JsonResult<T> ok() {
        return ok(null);
    }

    /**
     * - 返回成功状态
     *
     * @param data
     * @return
     * @author zJun
     * @date 2018年12月25日 下午2:02:54
     */
    public static <T> JsonResult<T> ok(T data) {
        return ok(null, data);
    }

    /**
     * - 返回成功状态
     *
     * @param msg
     * @param data
     * @return
     * @author zJun
     * @date 2018年12月25日 下午2:03:00
     */
    public static <T> JsonResult<T> ok(String msg, T data) {
        return create(ResStatus.OK, msg, data);
    }

    /**
     * - 返回失败状态
     *
     * @param msg
     * @return
     * @author zJun
     * @date 2018年12月25日 下午2:03:05
     */
    public static <T> JsonResult<T> fail(String msg) {
        return fail(msg, null);
    }

    public static <T> JsonResult<T> fail(String msg, T data) {
        return create(ResStatus.ILLEGAL, msg, data);
    }

    /**
     * - 返回失败状态
     *
     * @param code
     * @return
     * @author zJun
     * @date 2018年12月25日 下午2:03:05
     */
    public static <T> JsonResult<T> fail(IResStatus code) {
        return fail(code, null);
    }

    /**
     * - 返回失败状态
     *
     * @param code
     * @param msg
     * @return
     * @author zJun
     * @date 2018年12月25日 下午2:03:05
     */
    public static <T> JsonResult<T> fail(IResStatus code, String msg) {
        return create(code, msg, null);
    }

    /**
     * - 返回失败状态
     *
     * @param code
     * @param msg
     * @return
     * @author zJun
     * @date 2020年10月12日 下午4:33:33
     */
    public static <T> JsonResult<T> fail(Integer code, String msg) {
        return fail(code, msg, null);
    }

    public static <T> JsonResult<T> fail(Integer code, String msg, T data) {
        return create(code, msg, data);
    }

    /**
     * - 创建返回结果
     *
     * @param code
     * @param msg
     * @param data
     * @return
     * @author zJun
     * @date 2019年1月7日 下午3:56:48
     */
    public static <T> JsonResult<T> create(IResStatus code, String msg, T data) {
        return create(code.getCode(), msg == null ? code.getMessage() : msg, data);
    }

    /**
     * - 创建返回结果
     *
     * @param code
     * @param msg
     * @param data
     * @return
     * @author zJun
     * @date 2020年10月12日 下午4:09:02
     */
    public static <T> JsonResult<T> create(Integer code, String msg, T data) {
        return new JsonResult<>(code, msg, data);
    }

    /**
     * - 检查结果并返回泛型T数据
     *
     * @return
     * @author zJun
     * @date 2019年2月25日 下午9:29:52
     */
    public T checkRes() {
        if (this.getCode().compareTo(ResStatus.OK.getCode()) != 0) {
            throw SimpleException.getInstance(this.getCode(), this.getMsg());
        }
        return this.getData();
    }

    /**
     * - 检查结果并返回泛型T数据
     *
     * @param msg
     * @return
     * @author zJun
     * @date 2019年2月25日 下午9:31:36
     */
    public T checkRes(String msg) {
        if (this.getCode().compareTo(ResStatus.OK.getCode()) != 0) {
            throw SimpleException.getInstance(this.getCode(), msg);
        }
        return this.getData();
    }
}
