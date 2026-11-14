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

    private fun SourceCache.write(url: String, domains: Set<String>) {
        val staged = stage(url)
        domains.forEach(staged::add)
        staged.commit()
    }

    /** Everything saved for a source, or null if there is no saved copy. */
    private fun SourceCache.read(url: String): Set<String>? = savedFile(url)?.readLines()?.toSet()

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
    fun stagedCopyIsInvisibleUntilCommitted() {
        val cache = SourceCache(tmp.newFolder())
        cache.write(a, setOf("old.com"))
        val staged = cache.stage(a)
        staged.add("new.com")
        assertEquals(setOf("old.com"), cache.read(a))
        staged.commit()
        assertEquals(setOf("new.com"), cache.read(a))
    }

    @Test
    fun discardedCopyLeavesTheSavedOneAndNoTempFile() {
        val dir = tmp.newFolder()
        val cache = SourceCache(dir)
        cache.write(a, setOf("old.com"))
        val staged = cache.stage(a)
        staged.add("new.com")
        staged.discard()
        assertEquals(setOf("old.com"), cache.read(a))
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun discardAfterCommitKeepsTheCommittedCopy() {
        val cache = SourceCache(tmp.newFolder())
        val staged = cache.stage(a)
        staged.add("new.com")
        staged.commit()
        staged.discard()
        assertEquals(setOf("new.com"), cache.read(a))
    }

    @Test
    fun retainOnlyRemovesStrayTempFiles() {
        val dir = tmp.newFolder()
        val cache = SourceCache(dir)
        cache.write(a, setOf("x.com"))
        cache.stage(b).add("y.com")
        cache.retainOnly(listOf(a, b))
        assertEquals(listOf(true), dir.listFiles()!!.map { it.name.endsWith(".txt") })
    }

    @Test
    fun savedCopyHasOneDomainPerLine() {
        val cache = SourceCache(tmp.newFolder())
        cache.write(a, linkedSetOf("1.com", "2.com", "3.com"))
        assertEquals("1.com\n2.com\n3.com\n", cache.savedFile(a)!!.readText())
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
