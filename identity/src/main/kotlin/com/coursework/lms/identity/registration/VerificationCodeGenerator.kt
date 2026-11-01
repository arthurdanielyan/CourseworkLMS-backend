package com.coursework.lms.identity.registration

import org.springframework.stereotype.Component
import java.security.SecureRandom

/** Makes the six-digit Verification Codes emailed to new users. */
@Component
class VerificationCodeGenerator {

    private val random = SecureRandom()

    fun next(): String {
        return random.nextInt(CODE_SPACE).toString().padStart(CODE_LENGTH, '0')
    }

    private companion object {
        const val CODE_LENGTH = 6
        const val CODE_SPACE = 1_000_000
    }
}
