package com.wanderwildwood.utahon.sync

import com.wanderwildwood.utahon.data.Setlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetlistSyncTest {

    private class Phone : LocalLists {
        var all = listOf<Setlist>()
        var mark = "s0"
        private var n = 0
        override fun lists() = all
        override fun stamp() = mark
        override fun put(lists: List<Setlist>, expect: String): String? {
            if (mark != expect) return null
            all = lists
            mark = "s${++n}"
            return mark
        }
        override fun newId() = "new-${++n}"

        /** As the reader would: the setlists changed here. */
        fun change(lists: List<Setlist>) { all = lists; mark = "s${++n}" }
    }

    private val cloud = SyncTest.FakeCloud()
    private val phone = Phone()
    private var link: ListsLink? = null
    private fun sync() { link = SetlistSync.run(cloud, phone, link, " (phone)") }
    private fun there() = SetlistSync.fromFile(cloud.files.getValue(SetlistSync.FILE).text)!!

    private val friday = Setlist("f", "Friday", listOf("a", "b"))
    private val lesson = Setlist("l", "Lesson", listOf("c"))

    @Test fun nothingHereAndNothingThereIsNothing() {
        sync()
        assertNull(link)
        assertTrue(cloud.files.isEmpty())
    }

    @Test fun setlistsHereGoThereAndBackToAnotherPhone() {
        phone.change(listOf(friday))
        sync()
        assertEquals(listOf(friday), there())
        // A second phone, signed in to the same folder, with none of its own.
        val other = Phone()
        val otherLink = SetlistSync.run(cloud, other, null, " (phone)")
        assertEquals(listOf(friday), other.all)
        assertEquals(cloud.files.getValue(SetlistSync.FILE).etag, otherLink!!.etag)
    }

    @Test fun aFileThereThatCannotBeReadEmptiesNothingHere() {
        phone.change(listOf(friday))
        sync()
        cloud.putThere(SetlistSync.FILE, "{ not json")
        sync()
        assertEquals(listOf(friday), phone.all)
        assertEquals("{ not json", cloud.files.getValue(SetlistSync.FILE).text)
    }

    @Test fun aChangeOnOneSideReachesTheOther() {
        phone.change(listOf(friday))
        sync()
        phone.change(listOf(friday.copy(songs = listOf("b", "a")), lesson))
        sync()
        assertEquals(listOf("b", "a"), there().first().songs)
        assertEquals(2, there().size)
        cloud.putThere(SetlistSync.FILE, SetlistSync.toFile(listOf(lesson)))
        sync()
        assertEquals(listOf(lesson), phone.all)
        // Settled: nothing more is written.
        val etag = cloud.files.getValue(SetlistSync.FILE).etag
        sync()
        assertEquals(etag, cloud.files.getValue(SetlistSync.FILE).etag)
    }

    @Test fun changedOnBothSidesKeepsEverything() {
        phone.change(listOf(friday))
        sync()
        phone.change(listOf(friday.copy(songs = listOf("a")), lesson))
        cloud.putThere(SetlistSync.FILE, SetlistSync.toFile(listOf(friday.copy(songs = listOf("a", "b", "z")), Setlist("t", "Tuesday", listOf("q")))))
        sync()
        assertEquals(listOf("Friday", "Tuesday", "Friday (phone)", "Lesson"), phone.all.map { it.name })
        assertEquals(listOf("a", "b", "z"), phone.all.first().songs)
        assertEquals(listOf("a"), phone.all[2].songs)
        assertEquals(phone.all, there())
        // And a second sync makes no second copy.
        sync()
        assertEquals(4, phone.all.size)
    }

    @Test fun twoPhonesWithTheirOwnListsEndWithBoth() {
        cloud.putThere(SetlistSync.FILE, SetlistSync.toFile(listOf(lesson)))
        phone.change(listOf(friday))
        sync()
        assertEquals(listOf(lesson, friday), phone.all)
        assertEquals(listOf(lesson, friday), there())
    }

    @Test fun aFileChangedUnderTheWriteIsLeftForNextTime() {
        phone.change(listOf(friday))
        sync()
        phone.change(listOf(friday, lesson))
        cloud.meddle = { cloud.putThere(SetlistSync.FILE, SetlistSync.toFile(listOf(Setlist("t", "Tuesday", listOf("q"))))) }
        val before = link
        sync()
        assertEquals(before, link)
        sync()
        assertEquals(setOf("Friday", "Lesson", "Tuesday"), phone.all.map { it.name }.toSet())
    }

    @Test fun aChangeHereDuringTheSyncIsNotLost() {
        assertNull(phone.put(listOf(lesson), "not-the-stamp"))
        assertTrue(phone.all.isEmpty())
    }
}
