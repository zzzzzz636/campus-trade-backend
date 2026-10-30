package com.campus.campus_trade_backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail business(ResponseStatusException exception, HttpServletRequest request) {
        String detail = exception.getReason() == null ? "请求处理失败" : exception.getReason();
        return problem(exception.getStatusCode().value(), detail, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + "：" + error.getDefaultMessage())
                .distinct().collect(Collectors.joining("；"));
        return problem(HttpStatus.BAD_REQUEST.value(), detail.isBlank() ? "请求参数不合法" : detail, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail invalidParameter(ConstraintViolationException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST.value(), exception.getMessage(), request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail invalidMethodParameter(HandlerMethodValidationException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST.value(), "请求参数不合法", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail invalidParameterType(MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST.value(), "请求参数格式不正确：" + exception.getName(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST.value(), "请求体格式不正确", request);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ProblemDetail missingPart(MissingServletRequestPartException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST.value(), "缺少上传字段：" + exception.getRequestPartName(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail uploadTooLarge(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONTENT_TOO_LARGE.value(), "单张图片不能超过 5 MB，每件商品最多 6 张", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail internalState(IllegalStateException exception, HttpServletRequest request) {
        log.error("服务内部状态异常 path={}", request.getRequestURI(), exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务处理失败，请稍后重试", request);
    }

    private static ProblemDetail problem(int status, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setTitle(status >= 500 ? "服务暂不可用" : "请求未完成");
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
