package fyi.justfreesoftware.justblock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val a = "https://example.org/a.txt"
    private val b = "https://example.org/b.txt"

    @Test
    fun readsBackWhatWasWritten() {
        val cache = SourceCache(tmp.newFolder())
        cache.write(a, setOf("x.com", "y.com"))
        assertEquals(setOf("x.com", "y.com"), cache.read(a))
    }

    @Test
    fun missingSourceReadsAsNull() {
        assertNull(SourceCache(tmp.newFolder()).read(a))
    }

    @Test
    fun sourcesAreKeptSeparately() {
        val cache = SourceCache(tmp.newFolder())
        cache.write(a, setOf("x.com"))
        cache.write(b, setOf("y.com"))
        assertEquals(setOf("x.com"), cache.read(a))
        assertEquals(setOf("y.com"), cache.read(b))
    }

    @Test
    fun writeReplacesOldCopy() {
        val cache = SourceCache(tmp.newFolder())
        cache.write(a, setOf("old.com"))
        cache.write(a, setOf("new.com"))
        assertEquals(setOf("new.com"), cache.read(a))
    }

    @Test
    fun retainOnlyDeletesRemovedSources() {
        val dir = tmp.newFolder()
        val cache = SourceCache(dir)
        cache.write(a, setOf("x.com"))
        cache.write(b, setOf("y.com"))
        cache.retainOnly(listOf(a))
        assertEquals(setOf("x.com"), cache.read(a))
        assertNull(cache.read(b))
        assertEquals(1, dir.listFiles()!!.size)
    }
}
