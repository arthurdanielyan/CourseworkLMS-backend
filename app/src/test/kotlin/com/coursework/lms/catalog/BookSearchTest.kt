package com.coursework.lms.catalog

import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.SeedUsers
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get

// Uses the books V1 seeds.
class BookSearchTest : IntegrationTest() {

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Test
    fun `a search pages through every book sorted by title`() {
        search("size=5").andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(5) }
            jsonPath("$.page.total_elements") { value(count("select count(*) from books")) }
            jsonPath("$.content[0].title") { value(firstTitle()) }
        }
    }

    @Test
    fun `text matches the title ignoring case`() {
        search("q=CLEAN code").andExpect {
            jsonPath("$.content[0].id") { value(1) }
            jsonPath("$.content[0].title") { value("Clean Code") }
            jsonPath("$.content[0].publisher") { value("O’Reilly Media") }
            jsonPath("$.content[0].authors.length()") { value(2) }
            jsonPath("$.content[0].authors[0]") { value("Anton Chekhov") }
            jsonPath("$.content[0].authors[1]") { value("Martin Fowler") }
            jsonPath("$.content[0].rating") { value(4.7) }
            jsonPath("$.content[0].available") { value(true) }
            jsonPath("$.content[0].has_pdf") { value(false) }
        }
    }

    @Test
    fun `text matches an author's name too`() {
        search("q=knuth&size=100").andExpect {
            jsonPath("$.content[?(@.title == 'Refactoring')]") { exists() }
        }
    }

    @Test
    fun `wildcard characters in the text match themselves`() {
        search("q=%25").andExpect {
            jsonPath("$.page.total_elements") { value(0) }
        }
    }

    @Test
    fun `the author filter keeps only books by a matching author`() {
        val expected = count(
            """
            select count(distinct ba.book_id) from book_authors ba join authors a on a.id = ba.author_id
            where lower(a.name) like '%fowler%'
            """,
        )

        search("author=Fowler").andExpect {
            jsonPath("$.page.total_elements") { value(expected) }
        }
    }

    @Test
    fun `categories and languages match any of the listed ids`() {
        val expected = count(
            """
            select count(*) from books b
            where b.language_id in (1, 2)
              and exists (select 1 from books_categories bc where bc.book_id = b.id and bc.category_id in (2, 3))
            """,
        )

        search("category_id=2&category_id=3&language_id=1&language_id=2").andExpect {
            jsonPath("$.page.total_elements") { value(expected) }
        }
    }

    @Test
    fun `availability splits the catalog in two`() {
        search("available=true").andExpect {
            jsonPath("$.page.total_elements") { value(count("select count(*) from books where copies_available > 0")) }
        }
        search("available=false").andExpect {
            jsonPath("$.page.total_elements") { value(count("select count(*) from books where copies_available = 0")) }
        }
    }

    @Test
    fun `university and owner filters add up`() {
        val university = count("select count(*) from books where added_by is null")
        val teacher = count("select count(*) from books where added_by = ${SeedUsers.teacher.id}")

        search("university=true").andExpect {
            jsonPath("$.page.total_elements") { value(university) }
        }
        search("owner_id=${SeedUsers.teacher.id}").andExpect {
            jsonPath("$.page.total_elements") { value(teacher) }
        }
        search("owner_id=${SeedUsers.teacher.id}&university=true").andExpect {
            jsonPath("$.page.total_elements") { value(university + teacher) }
        }
    }

    @Test
    fun `the admin's books became University Books`() {
        search("owner_id=${SeedUsers.admin.id}").andExpect {
            jsonPath("$.page.total_elements") { value(0) }
        }
    }

    @Test
    fun `a malformed filter is a bad request`() {
        search("available=maybe").andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `searching needs a token`() {
        mockMvc.get("/api/v1/books").andExpect {
            status { isUnauthorized() }
        }
    }

    private fun search(query: String): ResultActionsDsl {
        return mockMvc.get("/api/v1/books?$query") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.student))
        }
    }

    private fun count(sql: String): Int {
        return jdbcClient.sql(sql).query(Int::class.java).single()
    }

    private fun firstTitle(): String {
        return jdbcClient.sql("select title from books order by title, id limit 1").query(String::class.java).single()
    }
}
