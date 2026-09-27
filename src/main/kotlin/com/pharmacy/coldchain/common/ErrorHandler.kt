package com.pharmacy.coldchain.common

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class ErrorHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException) = body(e.status, e.message)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException) =
        body(HttpStatus.BAD_REQUEST, "请求体格式错误: ${e.mostSpecificCause.message}")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException) =
        body(HttpStatus.BAD_REQUEST, "参数 ${e.name} 取值非法: ${e.value}")

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(e: MissingServletRequestParameterException) =
        body(HttpStatus.BAD_REQUEST, "缺少请求参数: ${e.parameterName}")

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(e: NoResourceFoundException) =
        body(HttpStatus.NOT_FOUND, "接口不存在: ${e.resourcePath}")

    @ExceptionHandler(Exception::class)
    fun handleOther(e: Exception): ResponseEntity<Map<String, Any>> {
        log.error("unhandled exception", e)
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误: ${e.message}")
    }

    private fun body(status: HttpStatus, message: String?): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(status).body(
            mapOf("error" to mapOf("code" to status.value(), "message" to (message ?: status.reasonPhrase)))
        )
}
