package com.coursework.lms

import com.coursework.lms.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the published API docs (`docs/api/openapi.json`) in step with the endpoints. Run with `lms.api-docs.write=true`
 * (`./gradlew :app:exportApiDocs`), it rewrites the file instead of checking it.
 */
@TestPropertySource(properties = ["springdoc.paths-to-exclude=/api/v1/test-probe/**"])
class ApiDocsTest : IntegrationTest() {

    private val mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()

    @Test
    fun `the published API docs match the endpoints`() {
        val current = currentDocs()
        val file = Path.of(System.getProperty("lms.api-docs.file"))
        if (System.getProperty("lms.api-docs.write").toBoolean()) {
            Files.writeString(file, mapper.writeValueAsString(current) + "\n")
            return
        }

        assertThat(Files.exists(file) && mapper.readTree(Files.readString(file)) == current)
            .withFailMessage("docs/api/openapi.json is out of date: run ./gradlew :app:exportApiDocs and commit it")
            .isTrue()
    }

    private fun currentDocs(): JsonNode {
        val body = mockMvc.get("/v3/api-docs").andReturn().response.contentAsString
        // The server URL is wherever the docs were generated, which means nothing on the published page.
        return (mapper.readTree(body) as ObjectNode).apply { remove("servers") }
    }
}
