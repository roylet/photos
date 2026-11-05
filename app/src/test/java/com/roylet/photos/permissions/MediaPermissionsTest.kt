package com.roylet.photos.permissions

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import com.roylet.photos.ui.permissions.MediaAccess
import com.roylet.photos.ui.permissions.MediaPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPermissionsTest {
    private fun access(sdk: Int, vararg granted: String) = MediaPermissions.accessFrom(sdk) { it in granted }

    @Test fun `android 14 and later - full, partial or denied`() {
        assertEquals(MediaAccess.Full, access(34, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED))
        assertEquals(MediaAccess.Full, access(35, READ_MEDIA_IMAGES))
        // "Select photos and videos" grants only the user-selected permission.
        assertEquals(MediaAccess.Partial, access(34, READ_MEDIA_VISUAL_USER_SELECTED))
        assertEquals(MediaAccess.Partial, access(35, READ_MEDIA_VISUAL_USER_SELECTED, ACCESS_MEDIA_LOCATION))
        assertEquals(MediaAccess.Denied, access(34))
        assertEquals(MediaAccess.Denied, access(34, READ_EXTERNAL_STORAGE)) // Ignored from 13 on.
    }

    @Test fun `android 13 has no partial access`() {
        assertEquals(MediaAccess.Full, access(33, READ_MEDIA_VIDEO))
        assertEquals(MediaAccess.Denied, access(33, READ_MEDIA_VISUAL_USER_SELECTED))
        assertEquals(MediaAccess.Denied, access(33))
    }

    @Test fun `android 10 to 12 use read external storage`() {
        for (sdk in 29..32) {
            assertEquals(MediaAccess.Full, access(sdk, READ_EXTERNAL_STORAGE))
            assertEquals(MediaAccess.Denied, access(sdk, READ_MEDIA_IMAGES))
            assertEquals(MediaAccess.Denied, access(sdk))
        }
    }

    @Test fun `what to request per version`() {
        assertEquals(listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED, ACCESS_MEDIA_LOCATION), MediaPermissions.requested(34).toList())
        assertEquals(listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, ACCESS_MEDIA_LOCATION), MediaPermissions.requested(33).toList())
        assertEquals(listOf(READ_EXTERNAL_STORAGE, ACCESS_MEDIA_LOCATION), MediaPermissions.requested(29).toList())
        assertEquals(READ_MEDIA_IMAGES, MediaPermissions.primary(35))
        assertEquals(READ_EXTERNAL_STORAGE, MediaPermissions.primary(30))
    }

    @Test fun `permanent denial needs an earlier prompt and no rationale`() {
        assertTrue(MediaPermissions.isPermanentlyDenied(MediaAccess.Denied, askedBefore = true, shouldShowRationale = false))
        // Never asked: the prompt will still show.
        assertFalse(MediaPermissions.isPermanentlyDenied(MediaAccess.Denied, askedBefore = false, shouldShowRationale = false))
        // Denied once: Android wants a rationale and will ask again.
        assertFalse(MediaPermissions.isPermanentlyDenied(MediaAccess.Denied, askedBefore = true, shouldShowRationale = true))
        assertFalse(MediaPermissions.isPermanentlyDenied(MediaAccess.Partial, askedBefore = true, shouldShowRationale = false))
        assertFalse(MediaPermissions.isPermanentlyDenied(MediaAccess.Full, askedBefore = true, shouldShowRationale = false))
    }
}
