package com.pharmacy.coldchain.common

import com.pharmacy.coldchain.domain.Role
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus

/**
 * 简化的演示鉴权：调用方通过请求头声明身份。
 *   X-User-Id: 用户标识，如 pharmacist-1 / rider-1 / patient-1
 *   X-Role:    PHARMACIST / WAREHOUSE / RIDER / PATIENT / CUSTOMER_SERVICE / INSURANCE / COMPENSATION / ADMIN
 * ADMIN 角色放行所有操作，便于演示与运维。
 */
object Auth {

    fun userId(req: HttpServletRequest): String =
        req.getHeader("X-User-Id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "缺少 X-User-Id 请求头")

    fun role(req: HttpServletRequest): Role {
        val raw = req.getHeader("X-Role")?.trim()
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "缺少 X-Role 请求头")
        return runCatching { Role.valueOf(raw.uppercase()) }.getOrNull()
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "非法角色: $raw")
    }

    /** 校验角色，返回 (userId, role)。roles 为空表示任意已认证角色。 */
    fun require(req: HttpServletRequest, vararg roles: Role): Pair<String, Role> {
        val uid = userId(req)
        val r = role(req)
        if (r != Role.ADMIN && roles.isNotEmpty() && r !in roles) {
            throw ForbiddenException("角色 $r 无权执行该操作，需要角色: ${roles.joinToString()}")
        }
        return uid to r
    }
}
