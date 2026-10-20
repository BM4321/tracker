package app.orb.tracker

import kotlin.test.Test
import kotlin.test.assertEquals

class AppNamesTest {
    @Test
    fun namesKnownAppsAndFallsBackToTheIdsReadablePart() {
        assertEquals("Cursor", appLabel("com.todesktop.230313mzl4w4u92"))
        assertEquals("YouTube", appLabel("com.google.android.youtube"))
        assertEquals("Xcode", appLabel("com.apple.dt.Xcode"))
        assertEquals("Example", appLabel("org.example.123456789"))
        assertEquals("Jetbrains", appLabel("com.jetbrains.*x"))
    }
}
