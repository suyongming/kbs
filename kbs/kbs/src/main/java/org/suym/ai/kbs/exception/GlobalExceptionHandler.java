package org.suym.ai.kbs.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.suym.ai.kbs.dto.base.JsonResult;

import jakarta.validation.ConstraintViolationException;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理自定义业务异常
     */
    @ExceptionHandler(SimpleException.class)
    public JsonResult<Void> handleSimpleException(SimpleException e) {
        log.error("Business Exception: code={}, msg={}", e.getCode(), e.getMsg());
        return JsonResult.fail(e.getCode(), e.getMsg());
    }

    /**
     * 处理参数校验异常 (Spring Validation)
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public JsonResult<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("Parameter Binding Exception: {}", msg);
        return JsonResult.fail(ResStatus.BAD_REQUEST.getCode(), msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public JsonResult<Void> handleConstraintViolationException(ConstraintViolationException e) {
        log.warn("Parameter Constraint Exception: {}", e.getMessage());
        return JsonResult.fail(ResStatus.BAD_REQUEST.getCode(), e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public JsonResult<Void> handleMissingParams(MissingServletRequestParameterException e) {
        log.warn("Missing Parameter: {}", e.getParameterName());
        return JsonResult.fail(ResStatus.BAD_REQUEST.getCode(), "Missing parameter: " + e.getParameterName());
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public JsonResult<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Illegal Argument: {}", e.getMessage());
        return JsonResult.fail(ResStatus.BAD_REQUEST.getCode(), e.getMessage());
    }

    /**
     * 处理所有未捕获的异常 (兜底)
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public JsonResult<Void> handleException(Exception e) {
        log.error("System Error: ", e);
        return JsonResult.fail(500, "System Error: " + e.getMessage());
    }
}
