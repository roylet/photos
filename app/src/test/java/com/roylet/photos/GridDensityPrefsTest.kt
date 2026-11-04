package com.roylet.photos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roylet.photos.ui.gallery.GridDensity
import com.roylet.photos.ui.gallery.GridDensityPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GridDensityPrefsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun remembersTheLastDensity() {
        assertNull(GridDensityPrefs.load(context))
        GridDensityPrefs.save(context, GridDensity.Duo)
        assertEquals(GridDensity.Duo, GridDensityPrefs.load(context))
        GridDensityPrefs.save(context, GridDensity.Feed)
        assertEquals(GridDensity.Feed, GridDensityPrefs.load(context))
    }

    @Test fun ignoresAnUnknownStoredName() {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("gridDensity", "Gigantic").commit()
        assertNull(GridDensityPrefs.load(context))
    }
}
