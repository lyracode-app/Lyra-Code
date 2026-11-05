package com.yukisoffd.lyracode.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DeveloperWallJsonTest {
    @Test fun readsSingleAndMultipleRolesWithoutCoercingNamesOrOptionalFields() {
        val people = parseDeveloperWallJson("""[
            {"name":" Alice ","avatar":" https://example.com/a.png ","role":[0,1,2,3,0],"link":" https://example.com "},
            {"name":"Bob","role":0},
            {"name":"Carol","role":3,"avatar":null,"link":false}
        ]""")
        assertEquals(listOf("Alice", "Bob", "Carol"), people.map { it.name })
        assertEquals(setOf(0, 1, 2, 3), people[0].roles)
        assertEquals("https://example.com/a.png", people[0].avatar)
        assertEquals("https://example.com", people[0].link)
        assertEquals(setOf(0), people[1].roles)
        assertEquals("", people[1].avatar)
        assertEquals("", people[2].link)
        assertEquals(2, people.count { 0 in it.roles })
        assertEquals(2, people.count { 3 in it.roles })
    }

    @Test fun skipsMissingBlankOrInvalidRequiredFieldsAndUnknownRoles() {
        val people = parseDeveloperWallJson("""[
            {}, {"name":"" ,"role":0}, {"name":"  ","role":1},
            {"role":0}, {"name":null,"role":0}, {"name":123,"role":0},
            {"name":"A"}, {"name":"B","role":null}, {"name":"C","role":"0"},
            {"name":"D","role":[]}, {"name":"E","role":[-1,4,1.5,"2",null,false]},
            null, 42, "invalid entry", {"name":"Valid","role":[7,2,2,"3"]}
        ]""")
        assertEquals(1, people.size)
        assertEquals("Valid", people.single().name)
        assertEquals(setOf(2), people.single().roles)
    }

    @Test fun rejectsMalformedOrUnrecognizedDocumentsIncludingLenientOrgJsonSyntax() {
        val invalid = listOf("", " ", "null", "{}", "{\"people\":[]}", "not json", "[",
            "[{\"name\":\"A\",\"role\":0}] trailing", "[{},]", "[;]", "[{name:'A',role:0}]",
            "/* comment */ []", "[{\"name\":\"A\",\"role\":01}]", "[NaN]", "[undefined]",
            "[true false]", "[\"bad\\xescape\"]", "[\"bad\nnewline\"]", "[\"\\u0q00\"]")
        invalid.forEach { text ->
            assertTrue("Must reject $text", runCatching { parseDeveloperWallJson(text) }.isFailure)
        }
        assertTrue(parseDeveloperWallJson("[]").isEmpty())
    }

    @Test fun acceptsEscapedNamesNumericRolesAndUnknownFields() {
        val person = parseDeveloperWallJson("""[{"name":"A\u4e2d\"\\","role":[0e0,1.0],"extra":{"a":true,"b":null}}]""").single()
        assertEquals("A中\"\\", person.name)
        assertEquals(setOf(0, 1), person.roles)
    }

    @Test fun documentedExampleIsUsableAndRoundTrips() {
        val people = parseDeveloperWallJson(File("../docs/developer-wall.example.json").readText())
        assertEquals(3, people.size)
        assertEquals(setOf(0, 1, 2), people[0].roles)
        assertEquals(people, parseDeveloperWallJson(developerWallPeopleJson(people).toString()))
    }

    @Test fun resolvesServerPathsAndOnlyAllowsWebProfileLinks() {
        val source = "https://example.com/api/wall.json"
        assertEquals("https://example.com/api/avatars/a.png", resolveWallAvatar(source, "avatars/a.png"))
        assertEquals("https://example.com/avatars/a.png", resolveWallAvatar(source, "/avatars/a.png"))
        assertEquals("https://cdn.example.com/a.png", resolveWallAvatar(source, "//cdn.example.com/a.png"))
        assertEquals("file:///missing/a.png", resolveWallAvatar(source, "file:///missing/a.png"))
        assertNull(resolveWallAvatar(source, "javascript:alert(1)"))
        assertNull(resolveWallAvatar(source, "content://other/person"))
        assertNull(wallHttpUrl("file:///profile"))
        assertNull(wallHttpUrl("https:///missing-host"))
        assertNull(wallHttpUrl("javascript:alert(1)"))
        assertNotNull(wallHttpUrl("https://github.com/example"))
    }
}
