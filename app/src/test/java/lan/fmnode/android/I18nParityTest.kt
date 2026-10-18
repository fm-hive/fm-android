package lan.fmnode.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both locales must define exactly the same string resources. */
class I18nParityTest {
    @Test
    fun `ru and en string resources have the same keys`() {
        val en = parse("src/main/res/values/strings.xml")
        val ru = parse("src/main/res/values-ru/strings.xml")
        assertTrue("no strings found", en.isNotEmpty())
        assertEquals(
            "missing in ru: ${en - ru}; missing in en: ${ru - en}",
            en,
            ru,
        )
    }

    private fun parse(path: String): Set<String> {
        val file = File(path).takeIf { it.exists() } ?: File("app/$path")
        require(file.exists()) { "not found: ${file.absolutePath}" }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val keys = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            val name = nodes.item(i).attributes.getNamedItem("name")?.nodeValue
            if (!name.isNullOrBlank()) keys += name
        }
        return keys
    }
}
