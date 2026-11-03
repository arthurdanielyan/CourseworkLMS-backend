package com.coursework.lms.identity.passwordreset

import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth/password-resets")
class PasswordResetController(
    private val passwordResetService: PasswordResetService,
    private val passwordResetSentResponseMapper: PasswordResetSentResponseMapper,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
        summary = "Emails a reset code; call it again to resend. " +
            "An email without an account gets the same answer, but no email.",
    )
    fun start(@Valid @RequestBody request: StartPasswordResetRequest): PasswordResetSentResponse {
        return passwordResetSentResponseMapper.map(passwordResetService.start(request.email))
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Checks the code without using it up, before the new password is chosen")
    fun verify(@Valid @RequestBody request: VerifyPasswordResetRequest) {
        passwordResetService.verify(request.email, request.code)
    }

    @PostMapping("/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sets the new password and logs the user out on every device; they log in again with it")
    fun complete(@Valid @RequestBody request: CompletePasswordResetRequest) {
        passwordResetService.complete(
            email = request.email,
            code = request.code,
            newPassword = request.newPassword,
        )
    }
}
