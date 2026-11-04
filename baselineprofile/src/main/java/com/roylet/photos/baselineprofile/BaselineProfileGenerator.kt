package com.roylet.photos.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the app's hot paths into a Baseline Profile:
 *
 *     ./gradlew :app:generateBaselineProfile
 *
 * Cold start always runs. The timeline, viewer and editor journeys need a signed-in app: open
 * Photos on the device once and connect to your Immich server before generating (app data is
 * kept between iterations). Signed out, only the start-up and connect screen are recorded.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        // Cold start to the first frame of the timeline (or the connect screen).
        pressHome()
        startActivityAndWait()
        val grid = device.wait(Until.findObject(By.res(TIMELINE)), 10_000) ?: return@collect

        // Scroll the timeline: month headers, justified rows, thumbnails.
        grid.setGestureMargin(device.displayWidth / 8)
        repeat(3) { grid.fling(Direction.DOWN); device.waitForIdle() }
        repeat(2) { grid.fling(Direction.UP); device.waitForIdle() }

        // Open the viewer on a photo and page through a few.
        device.click(device.displayWidth / 4, device.displayHeight / 3)
        val pager = device.wait(Until.findObject(By.res(VIEWER)), 5_000) ?: return@collect
        pager.setGestureMargin(device.displayWidth / 6)
        repeat(4) { pager.swipe(Direction.LEFT, 0.8f); device.waitForIdle() }
        pager.swipe(Direction.RIGHT, 0.8f)
        device.waitForIdle()

        // Open the editor (downloads the original, then the GPU preview and tool panels).
        device.findObject(By.desc("Edit"))?.click() ?: return@collect
        device.wait(Until.hasObject(By.desc("Close editor")), 20_000)
        device.waitForIdle()
        device.pressBack()
        device.findObject(By.text("Discard"))?.click()
        device.pressBack()
    }

    private companion object {
        const val PACKAGE = "com.roylet.photos"
        const val TIMELINE = "timeline-grid"
        const val VIEWER = "viewer-pager"
    }
}
