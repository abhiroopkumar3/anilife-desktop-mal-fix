package me.enivzoy.anilife.app.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ApiCredentialsTest {
    @TempDir lateinit var dir: File

    private fun credentials(env: Map<String, String> = emptyMap()) = ApiCredentials(
        dataFile = File(dir, "data/.env"),
        installFile = File(dir, "install/.env"),
        environment = { env[it] },
    )

    @Test
    fun `a fresh file has the three keys and every value empty`() {
        val credentials = credentials()
        credentials.ensureFile()
        val text = File(dir, "data/.env").readText()
        ApiKey.entries.forEach { assertTrue("${it.variable}=\n" in text, it.variable) }
        ApiKey.entries.forEach { assertFalse(credentials.isSet(it)) }
    }

    @Test
    fun `an existing file is never overwritten`() {
        val file = File(dir, "data/.env").apply { parentFile.mkdirs(); writeText("MAL_CLIENT_ID=keepme\n") }
        credentials().ensureFile()
        assertEquals("MAL_CLIENT_ID=keepme\n", file.readText())
    }

    @Test
    fun `saving one service leaves the other's values and the comments alone`() {
        val credentials = credentials()
        credentials.save(mapOf(ApiKey.ANILIST_CLIENT_ID to "111", ApiKey.ANILIST_CLIENT_SECRET to "sss"))
        credentials.save(mapOf(ApiKey.MAL_CLIENT_ID to "mal-1"))
        assertEquals("mal-1", credentials.read(ApiKey.MAL_CLIENT_ID))
        assertEquals("111", credentials.read(ApiKey.ANILIST_CLIENT_ID))
        assertEquals("sss", credentials.read(ApiKey.ANILIST_CLIENT_SECRET))
        assertTrue("# AniLife API settings" in File(dir, "data/.env").readText())
        credentials.save(mapOf(ApiKey.ANILIST_CLIENT_ID to "222"))
        assertEquals("mal-1", credentials.read(ApiKey.MAL_CLIENT_ID))
        assertEquals("sss", credentials.read(ApiKey.ANILIST_CLIENT_SECRET))
        assertEquals(1, File(dir, "data/.env").readLines().count { it.startsWith("ANILIST_CLIENT_ID=") })
    }

    @Test
    fun `the saved file wins over a hand-pasted one beside the program, which still works when nothing is saved`() {
        File(dir, "install").mkdirs()
        File(dir, "install/.env").writeText("MAL_CLIENT_ID=\"pasted\"\nANILIST_CLIENT_ID=77\n")
        val credentials = credentials()
        assertEquals("pasted", credentials.read(ApiKey.MAL_CLIENT_ID))
        credentials.ensureFile() // blank values in the data file do not hide the pasted ones
        assertEquals("pasted", credentials.read(ApiKey.MAL_CLIENT_ID))
        credentials.save(mapOf(ApiKey.MAL_CLIENT_ID to "typed"))
        assertEquals("typed", credentials.read(ApiKey.MAL_CLIENT_ID))
        assertEquals("77", credentials.read(ApiKey.ANILIST_CLIENT_ID))
    }

    @Test
    fun `an environment variable beats both files`() {
        val credentials = credentials(mapOf("MAL_CLIENT_ID" to "from-env"))
        credentials.save(mapOf(ApiKey.MAL_CLIENT_ID to "typed"))
        assertEquals("from-env", credentials.read(ApiKey.MAL_CLIENT_ID))
    }

    @Test
    fun `a value cannot smuggle a second line into the file`() {
        val credentials = credentials()
        credentials.save(mapOf(ApiKey.MAL_CLIENT_ID to "abc\nANILIST_CLIENT_ID=evil"))
        assertFalse(credentials.isSet(ApiKey.ANILIST_CLIENT_ID))
    }
}
