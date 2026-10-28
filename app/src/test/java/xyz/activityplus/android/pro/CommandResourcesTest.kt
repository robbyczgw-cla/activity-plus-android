package xyz.activityplus.android.pro

import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandResourcesTest {
    @Test fun closesInReverseOrderAndClosesLateResources() {
        val closed = mutableListOf<Int>()
        val resources = CommandResources()
        resources.add(Closeable { closed.add(1) })
        resources.add(Closeable { closed.add(2) })
        resources.close()
        resources.close()
        resources.add(Closeable { closed.add(3) })
        assertEquals(listOf(2, 1, 3), closed)
    }
}
