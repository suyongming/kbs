package org.suym.ai.kbs.exception;

/**
 * - 状态码
 *
 * @author zJun
 * @date 2020年9月21日 下午6:35:13
 */
public interface IResStatus {

    /**
     * - 获取状态码
     *
     * @return
     * @author zJun
     * @date 2020年9月21日 下午6:35:27
     */
    int getCode();

    /**
     * - 获取信息
     *
     * @return
     * @author zJun
     * @date 2020年9月21日 下午6:43:17
     */
    String getMessage();
}
