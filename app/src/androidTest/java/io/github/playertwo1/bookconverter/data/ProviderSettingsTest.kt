package io.github.playertwo1.bookconverter.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playertwo1.bookconverter.core.provider.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProviderSettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fake = "sk-test-NOT-A-REAL-KEY-0123456789"

    @Test fun keyRoundTripsAndIsNeverStoredInPlainText() {
        val s = ProviderSettings(context)
        s.setKey(Provider.OPENAI, " $fake ")
        assertEquals(fake, s.key(Provider.OPENAI))
        // A new instance reads the same key back (no in-memory cache).
        assertEquals(fake, ProviderSettings(context).key(Provider.OPENAI))
        val prefsFile = File(context.dataDir, "shared_prefs/providers.xml")
        // apply() writes in the background; wait until the entry is on disk.
        val deadline = System.currentTimeMillis() + 5_000
        while (!(prefsFile.exists() && prefsFile.readText().contains("key.OPENAI")) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("entrada da chave não foi gravada", prefsFile.readText().contains("key.OPENAI"))
        assertFalse("chave em texto puro no SharedPreferences", prefsFile.readText().contains("NOT-A-REAL-KEY"))
        // Nothing in the Room database either.
        context.databaseList().forEach { name ->
            val f = context.getDatabasePath(name)
            if (f.isFile) assertFalse("chave em $name", String(f.readBytes(), Charsets.ISO_8859_1).contains("NOT-A-REAL-KEY"))
        }
        s.setKey(Provider.OPENAI, "")
        assertNull(s.key(Provider.OPENAI))
        assertFalse(s.hasKey(Provider.OPENAI))
    }

    @Test fun baseUrlMustBeHttps() {
        val s = ProviderSettings(context)
        assertFalse(s.setBaseUrl(Provider.GEMINI, "http://gateway.example/v1beta"))
        assertTrue(s.setBaseUrl(Provider.GEMINI, "https://gateway.example/v1beta/"))
        assertEquals("https://gateway.example/v1beta", s.baseUrl(Provider.GEMINI))
        assertTrue(s.setBaseUrl(Provider.GEMINI, " "))
        assertNull(s.baseUrl(Provider.GEMINI))
    }

    @Test fun rejectedEffortIsRememberedPerModel() {
        val s = ProviderSettings(context)
        s.rejectEffort(Provider.ANTHROPIC, "model-a", "high")
        assertEquals(setOf("high"), s.rejectedEfforts(Provider.ANTHROPIC, "model-a"))
        assertEquals(emptySet<String>(), s.rejectedEfforts(Provider.ANTHROPIC, "model-b"))
    }
}
