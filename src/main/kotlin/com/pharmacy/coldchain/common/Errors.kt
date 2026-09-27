package com.pharmacy.coldchain.common

import org.springframework.http.HttpStatus

open class ApiException(val status: HttpStatus, override val message: String) : RuntimeException(message)

class NotFoundException(message: String) : ApiException(HttpStatus.NOT_FOUND, message)

class ConflictException(message: String) : ApiException(HttpStatus.CONFLICT, message)

class BadRequestException(message: String) : ApiException(HttpStatus.BAD_REQUEST, message)

class ForbiddenException(message: String) : ApiException(HttpStatus.FORBIDDEN, message)
