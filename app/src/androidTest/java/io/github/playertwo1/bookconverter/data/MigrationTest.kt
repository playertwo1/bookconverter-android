// Each schema bump adds a test that runs helper.runMigrationsAndValidate(name, N, true, MIGRATION_X_N)
package io.github.playertwo1.bookconverter.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun version1CreatesAndOpens() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL(
                "INSERT INTO Book (title, author, language, format, sha256, privatePath, sourceName, sizeBytes, importedAt) " +
                    "VALUES ('t', NULL, 'en', 'EPUB', 'abc', '/x', 'x.epub', 1, 0)"
            )
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "migration-test")
            // Future migrations go in .addMigrations here.
            .build()
        runBlocking {
            assertEquals(1, db.books().all().first().size)
        }
        db.close()
    }
}
