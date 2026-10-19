package com.coursework.lms.identity.registration

import com.coursework.lms.identity.session.TokenPairResponse
import com.coursework.lms.identity.session.TokenPairResponseMapper
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth/registrations")
class RegistrationController(
    private val registrationService: RegistrationService,
    private val pendingRegistrationResponseMapper: PendingRegistrationResponseMapper,
    private val tokenPairResponseMapper: TokenPairResponseMapper,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(@Valid @RequestBody request: StartRegistrationRequest): PendingRegistrationResponse {
        val pending = registrationService.start(
            email = request.email,
            fullName = request.fullName,
            password = request.password,
        )
        return pendingRegistrationResponseMapper.map(pending)
    }

    @PostMapping("/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun resend(@Valid @RequestBody request: ResendCodeRequest): PendingRegistrationResponse {
        return pendingRegistrationResponseMapper.map(registrationService.resend(request.email))
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.CREATED)
    fun verify(@Valid @RequestBody request: VerifyCodeRequest): TokenPairResponse {
        val tokens = registrationService.verify(
            email = request.email,
            code = request.code,
        )
        return tokenPairResponseMapper.map(tokens)
    }
}
