package com.coursework.lms.identity.session

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class SessionController(
    private val sessionService: SessionService,
    private val tokenPairResponseMapper: TokenPairResponseMapper,
) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): TokenPairResponse {
        val tokens = sessionService.login(
            email = request.email,
            password = request.password,
        )
        return tokenPairResponseMapper.map(tokens)
    }

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): TokenPairResponse {
        return tokenPairResponseMapper.map(sessionService.refresh(request.refreshToken))
    }
}
