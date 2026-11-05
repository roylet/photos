package com.roylet.photos.local

import com.roylet.photos.domain.timeline.TimelineAsset
import com.roylet.photos.local.FakeMediaStore.Companion.photo
import com.roylet.photos.local.FakeMediaStore.Companion.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/** Pure rules of the phone index: dates, months, ratios, search dates, EXIF labels. */
class LocalModelTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")
    private val la = ZoneId.of("America/Los_Angeles")

    @Test fun `taken-at falls back from DATE_TAKEN to DATE_ADDED to DATE_MODIFIED`() {
        val base = photo(1)
        assertEquals(1_700_000_000_123L, base.copy(dateTaken = 1_700_000_000_123L).takenAtMillis())
        assertEquals(1_600_000_000_000L, base.copy(dateTaken = null, dateAdded = 1_600_000_000).takenAtMillis())
        assertEquals(1_600_000_000_000L, base.copy(dateTaken = 0, dateAdded = 1_600_000_000).takenAtMillis())
        assertEquals(1_500_000_000_000L, base.copy(dateTaken = null, dateAdded = 0, dateModified = 1_500_000_000).takenAtMillis())
        assertEquals(0L, base.copy(dateTaken = null, dateAdded = null, dateModified = null).takenAtMillis())
    }

    @Test fun `months are wall-clock months in the device zone`() {
        val t = utc(2026, 1, 31, 23, 30)
        assertEquals(202602, MonthKeys.monthOf(t, melbourne))
        assertEquals(202601, MonthKeys.monthOf(t, la))
        assertEquals(202601, MonthKeys.monthOf(t, ZoneId.of("UTC")))
        // New Year's Eve in Melbourne is still 2025 in UTC.
        assertEquals(202601, MonthKeys.monthOf(utc(2025, 12, 31, 14), melbourne))
        // DST: Melbourne is +11 in January, +10 in July.
        assertEquals(660, MonthKeys.offsetMinutes(utc(2026, 1, 15), melbourne))
        assertEquals(600, MonthKeys.offsetMinutes(utc(2026, 7, 15), melbourne))
    }

    @Test fun `bucket keys use Immich's format and parse back`() {
        assertEquals("2026-02-01T00:00:00.000Z", MonthKeys.key(202602))
        assertEquals("1999-12-01T00:00:00.000Z", MonthKeys.key(199912))
        assertEquals(202602, MonthKeys.parse("2026-02-01T00:00:00.000Z"))
        assertNull(MonthKeys.parse("2026-13-01T00:00:00.000Z"))
        assertNull(MonthKeys.parse("nope"))
    }

    @Test fun `entity carries the local offset so the asset's local time is right`() {
        val e = photo(1, taken = utc(2026, 1, 31, 23, 30)).toEntity(melbourne)
        val a = e.toAsset()
        assertEquals("local:1", a.id)
        assertEquals(11.0, a.localOffsetHours, 0.0)
        assertEquals(utc(2026, 2, 1, 10, 30), a.localTakenAt)
    }

    @Test fun `ratio applies orientation`() {
        assertEquals(4f / 3f, displayRatio(4000, 3000, 0), 1e-6f)
        assertEquals(3f / 4f, displayRatio(4000, 3000, 90), 1e-6f)
        assertEquals(4f / 3f, displayRatio(4000, 3000, 180), 1e-6f)
        assertEquals(3f / 4f, displayRatio(4000, 3000, 270), 1e-6f)
        assertEquals(1f, displayRatio(0, 3000, 0), 0f)
        assertEquals(3f / 4f, photo(1).copy(orientation = 90).toEntity(melbourne).toAsset().ratio, 1e-6f)
    }

    @Test fun `videos keep their duration, photos have none`() {
        assertEquals(12_000L, FakeMediaStore.video(2).toEntity(melbourne).toAsset().durationMs)
        assertNull(photo(1).copy(durationMs = 5).toEntity(melbourne).toAsset().durationMs)
    }

    @Test fun `expiry is only kept for trashed rows`() {
        assertNull(photo(1).copy(dateExpires = 1_800_000_000).toEntity(melbourne).expiresAt)
        assertEquals(1_800_000_000_000L, photo(1).copy(isTrashed = true, dateExpires = 1_800_000_000).toEntity(melbourne).expiresAt)
    }

    @Test fun `days left rounds up and never goes negative`() {
        val day = 86_400_000L
        val now = 1_000 * day
        fun left(exp: Long?) = TimelineAsset("local:1", 1f, true, false, isTrashed = true, expiresAt = exp).daysLeft(now)
        assertEquals(30, left(now + 30 * day))
        assertEquals(1, left(now + 1))
        assertEquals(0, left(now - day))
        assertNull(left(null))
    }

    @Test fun `search dates`() {
        assertEquals(DateQuery.Months(202401, 202412), DateQuery.parse("2024"))
        assertEquals(DateQuery.Months(202303, 202303), DateQuery.parse("March 2023"))
        assertEquals(DateQuery.Months(202303, 202303), DateQuery.parse("mar 2023"))
        assertEquals(DateQuery.Months(202303, 202303), DateQuery.parse("2023 March"))
        assertEquals(DateQuery.Months(202303, 202303), DateQuery.parse("2023-03"))
        assertEquals(DateQuery.Months(202312, 202312), DateQuery.parse("Dec 23"))
        assertEquals(DateQuery.MonthOfYear(5), DateQuery.parse("May"))
        assertNull(DateQuery.parse("beach"))
        assertNull(DateQuery.parse("IMG_2024"))
        assertNull(DateQuery.parse("ma"))
    }

    @Test fun `LIKE wildcards are escaped`() {
        assertEquals("IMG\\_1\\%", MediaStoreLibrary.likeEscape("IMG_1%"))
    }

    @Test fun `exposure and video location labels`() {
        assertEquals("1/1200", exposureLabel(1.0 / 1200))
        assertEquals("1/8", exposureLabel(0.125))
        assertEquals("2", exposureLabel(2.0))
        assertEquals("2.5", exposureLabel(2.5))
        assertEquals(37.7749 to -122.4194, parseIso6709("+37.7749-122.4194/"))
        assertEquals(-37.8136 to 144.9631, parseIso6709("-37.8136+144.9631+010.000/"))
        assertNull(parseIso6709("+00.0000+000.0000/"))
        assertNull(parseIso6709(null))
    }
}
