package it.kapfer.bankteller

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [parseQueryParams] — the query-string parser behind the callback
 * screen's session/redirect identifier display (task 10.7, D13).
 */
class QueryParamsTest {

    @Test
    fun parses_state_and_code() {
        assertEquals(
            mapOf("state" to "abc", "code" to "xyz"),
            parseQueryParams("?state=abc&code=xyz"),
        )
    }

    @Test
    fun parses_auth_flow_params() {
        assertEquals(
            mapOf("state" to "s1", "oobCode" to "k4bl1e"),
            parseQueryParams("?state=s1&oobCode=k4bl1e"),
        )
    }

    @Test
    fun empty_search_yields_empty_map() {
        assertEquals(emptyMap(), parseQueryParams(""))
    }

    @Test
    fun bare_question_mark_yields_empty_map() {
        assertEquals(emptyMap(), parseQueryParams("?"))
    }

    @Test
    fun search_without_question_mark_prefix_is_accepted() {
        assertEquals(mapOf("a" to "1"), parseQueryParams("a=1"))
    }

    @Test
    fun entries_without_equals_are_dropped() {
        assertEquals(mapOf("a" to "1"), parseQueryParams("?flare&a=1"))
    }

    @Test
    fun values_without_highlighting_are_taken_verbatim() {
        // Identifiers are displayed as-is (not URL-decoded) on the callback screen.
        assertEquals(mapOf("state" to "a%2Bb"), parseQueryParams("?state=a%2Bb"))
    }
}