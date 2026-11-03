package com.anand.envoymonitor

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import com.anand.envoymonitor.ui.SolarLineChart

/** Exercises the shipped screen and real two-finger events, without inserting history. */
open class ChartInteractionInstrumentation : Instrumentation() {
    private lateinit var activity: Activity
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun waitFor(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 12000
        while (!condition()) { check(SystemClock.uptimeMillis() < until) { message }; SystemClock.sleep(100) }
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null || node.isPassword) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }
    private fun ownScreen() { check(uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.anand.envoymonitor") { "Phone switched to another app; stopped screen input" } }
    private fun click(label: String) {
        waitFor("Missing control: $label") { find(uiAutomation.rootInActiveWindow, label) != null }
        ownScreen()
        var node = find(uiAutomation.rootInActiveWindow, label)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not click $label" }
        SystemClock.sleep(300)
    }
    private fun nativeChart(): SolarLineChart? {
        var found: SolarLineChart? = null
        fun search(view: View) {
            if (view is SolarLineChart) found = view
            else if (view is ViewGroup) for (i in 0 until view.childCount) search(view.getChildAt(i))
        }
        runOnMainSync { search(activity.window.decorView) }
        return found
    }
    private fun pinch(chart: SolarLineChart, outward: Boolean) {
        ownScreen()
        val rect = Rect()
        runOnMainSync { check(chart.getGlobalVisibleRect(rect)) }
        val centerX = rect.exactCenterX(); val centerY = rect.exactCenterY()
        val small = rect.width() * .06f; val big = rect.width() * .25f
        val startDistance = if (outward) small else big
        val endDistance = if (outward) big else small
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int, distance: Float, count: Int) {
            ownScreen()
            val properties = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { index -> MotionEvent.PointerCoords().apply {
                x = centerX + if (index == 0) -distance else distance; y = centerY; pressure = 1f; size = 1f
            } }
            val motion = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            check(uiAutomation.injectInputEvent(motion, true)) { "Touch injection failed" }; motion.recycle()
        }
        event(MotionEvent.ACTION_DOWN, startDistance, 1)
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), startDistance, 2)
        repeat(16) { index -> event(MotionEvent.ACTION_MOVE, startDistance + (endDistance - startDistance) * (index + 1) / 16, 2); SystemClock.sleep(20) }
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), endDistance, 2)
        event(MotionEvent.ACTION_UP, endDistance, 1)
        SystemClock.sleep(400)
    }
    override fun onStart() {
        val result = Bundle()
        try {
            val monitor = addMonitor("com.anand.envoymonitor.MainActivity", null, false)
            targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = monitor.waitForActivityWithTimeout(10000) ?: error("Activity did not launch")
            waitFor("Dashboard did not appear") { find(uiAutomation.rootInActiveWindow, "GATEWAY VOLTAGE") != null }
            check(find(uiAutomation.rootInActiveWindow, "Checking gateway…") == null) { "Polling banner remains" }
            click("History")
            waitFor("History chart missing") { nativeChart() != null }
            var chart = nativeChart()!!
            SystemClock.sleep(1500)
            if (chart.data == null || chart.data.entryCount == 0) { click("1 hour ▾"); click("30 days") }
            waitFor("No recorded readings available for zoom regression") { nativeChart()?.data?.entryCount?.let { it > 0 } == true }
            chart = nativeChart()!!
            click("Zoom +")
            runOnMainSync { check(chart.viewPortHandler.scaleX > 1f) { "Zoom button did not zoom" } }
            repeat(3) { pinch(chart, true); pinch(chart, false) }
            click("Area ▾"); click("Line")
            waitFor("Line selection failed") { find(uiAutomation.rootInActiveWindow, "Line ▾") != null }
            ownScreen(); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor("System Back did not return home") { find(uiAutomation.rootInActiveWindow, "GATEWAY VOLTAGE") != null }
            click("History"); click("Back")
            waitFor("App Back did not return home") { find(uiAutomation.rootInActiveWindow, "GATEWAY VOLTAGE") != null }
            click("Settings"); ownScreen(); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitFor("Settings Back did not return home") { find(uiAutomation.rootInActiveWindow, "GATEWAY VOLTAGE") != null }
            result.putString("stream", "PASS: zoom button, six real two-finger pinch/release gestures, Area/Line switch, system/app Back from History, Settings Back and absent polling banner.\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
