package com.yukisoffd.lyracode.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class DeveloperWallPerson(
    val name: String,
    val avatar: String,
    val roles: Set<Int>,
    val link: String,
    val avatarFile: File? = null,
)

/** The wire format is an array; role accepts either a number or an array of numbers. */
internal fun parseDeveloperWallJson(text: String): List<DeveloperWallPerson> {
    // Android's org.json accepts non-JSON syntax, including comments and single quotes.
    // Validate the wire syntax first so a broken server response never becomes a wall.
    StrictWallJson(text).validate()
    val array = JSONArray(text)
    return (0 until array.length()).mapNotNull { index ->
        val person = array.optJSONObject(index) ?: return@mapNotNull null
        val name = (person.opt("name") as? String)?.trim().orEmpty()
        val role = person.opt("role")
        val roles = (if (role is JSONArray) (0 until role.length()).map(role::opt) else listOf(role))
            .mapNotNull { value ->
                (value as? Number)?.toDouble()?.takeIf { it in 0.0..3.0 && it % 1.0 == 0.0 }?.toInt()
            }.toSet()
        if (name.isEmpty() || roles.isEmpty()) return@mapNotNull null
        DeveloperWallPerson(
            name = name,
            avatar = (person.opt("avatar") as? String)?.trim().orEmpty(),
            roles = roles,
            link = (person.opt("link") as? String)?.trim().orEmpty(),
        )
    }
}

internal fun developerWallPeopleJson(people: List<DeveloperWallPerson>): JSONArray = JSONArray().apply {
    people.forEach { person ->
        put(JSONObject().put("name", person.name).put("avatar", person.avatar)
            .put("role", JSONArray(person.roles.toList())).put("link", person.link))
    }
}

private class StrictWallJson(private val text: String) {
    private var index = 0

    fun validate() {
        value(0)
        whitespace()
        require(index == text.length) { "Trailing JSON content" }
    }

    private fun whitespace() {
        while (index < text.length && text[index] in " \t\r\n") index++
    }

    private fun take(char: Char): Boolean {
        whitespace()
        return (text.getOrNull(index) == char).also { if (it) index++ }
    }

    private fun value(depth: Int) {
        require(depth < 32) { "JSON nesting is too deep" }
        whitespace()
        when (text.getOrNull(index)) {
            '"' -> string()
            '{' -> {
                index++
                if (take('}')) return
                do {
                    whitespace()
                    string()
                    require(take(':'))
                    value(depth + 1)
                } while (take(','))
                require(take('}'))
            }
            '[' -> {
                index++
                if (take(']')) return
                do { value(depth + 1) } while (take(','))
                require(take(']'))
            }
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val match = NUMBER.find(text, index)
                require(match != null && match.range.first == index) { "Invalid JSON value" }
                index = match.range.last + 1
            }
        }
    }

    private fun literal(value: String) {
        require(text.startsWith(value, index))
        index += value.length
    }

    private fun string() {
        require(text.getOrNull(index++) == '"')
        while (index < text.length) {
            val char = text[index++]
            if (char == '"') return
            require(char >= ' ') { "Unescaped control character" }
            if (char == '\\') {
                val escaped = text.getOrNull(index++)
                if (escaped == 'u') {
                    repeat(4) { require(text.getOrNull(index++)?.digitToIntOrNull(16) != null) }
                } else {
                    require(escaped != null && escaped in "\"\\/bfnrt")
                }
            }
        }
        error("Unterminated JSON string")
    }

    companion object {
        private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}
