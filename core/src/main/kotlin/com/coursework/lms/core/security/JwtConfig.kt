package com.coursework.lms.core.security

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableConfigurationProperties(JwtProperties::class)
class JwtConfig(
    private val properties: JwtProperties,
) {

    private val secretKey: SecretKey by lazy {
        val bytes = properties.secret.toByteArray()
        // HS256 needs a key at least as long as its 256-bit output, or Nimbus refuses to sign.
        require(bytes.size >= MIN_SECRET_BYTES) { "lms.jwt.secret must be at least $MIN_SECRET_BYTES bytes long" }
        SecretKeySpec(bytes, "HmacSHA256")
    }

    @Bean
    fun jwtDecoder(): JwtDecoder {
        return NimbusJwtDecoder.withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
            .apply { setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer)) }
    }

    @Bean
    fun jwtEncoder(): JwtEncoder {
        return NimbusJwtEncoder.withSecretKey(secretKey).build()
    }

    private companion object {
        const val MIN_SECRET_BYTES = 32
    }
}
