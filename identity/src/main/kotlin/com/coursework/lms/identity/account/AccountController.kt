package com.coursework.lms.identity.account

import com.coursework.lms.core.security.CurrentUser
import com.coursework.lms.identity.session.TokenPairResponse
import com.coursework.lms.identity.session.TokenPairResponseMapper
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/me")
class AccountController(
    private val accountService: AccountService,
    private val accountResponseMapper: AccountResponseMapper,
    private val tokenPairResponseMapper: TokenPairResponseMapper,
) {

    @GetMapping
    @Operation(summary = "The logged-in user: name, email, role and permissions")
    fun me(@AuthenticationPrincipal user: CurrentUser): AccountResponse {
        return accountResponseMapper.map(accountService.find(user.id))
    }

    @PutMapping("/name")
    @Operation(summary = "Changes the logged-in user's name")
    fun rename(
        @Valid @RequestBody request: RenameRequest,
        @AuthenticationPrincipal user: CurrentUser,
    ): AccountResponse {
        return accountResponseMapper.map(accountService.rename(user.id, request.fullName.trim()))
    }

    @PostMapping("/password")
    @Operation(
        summary = "Changes the password and logs out every other device; " +
            "this device continues with the token pair in the response",
    )
    fun changePassword(
        @Valid @RequestBody request: ChangePasswordRequest,
        @AuthenticationPrincipal user: CurrentUser,
    ): TokenPairResponse {
        val tokens = accountService.changePassword(
            userId = user.id,
            currentPassword = request.currentPassword,
            newPassword = request.newPassword,
        )
        return tokenPairResponseMapper.map(tokens)
    }
}
