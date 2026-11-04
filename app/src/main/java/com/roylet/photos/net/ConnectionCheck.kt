package com.roylet.photos.net

/** Outcome of one diagnostics step. */
enum class StepStatus { Pass, Warn, Fail, Running, Skipped }

/** One line of the Settings "Connection check" panel. */
data class CheckStep(val name: String, val status: StepStatus, val detail: String)

/**
 * What the last manual local-address check or LAN scan did, step by step, so the user can see (and
 * tell us) exactly which stage failed: device network, ping, fingerprint, account check, result.
 */
data class ConnectionCheck(val title: String, val steps: List<CheckStep>, val running: Boolean = false) {
    val passed: Boolean get() = !running && steps.lastOrNull()?.status.let { it == StepStatus.Pass || it == StepStatus.Warn }

    /** Plain text, for copying into a bug report. */
    fun asText(): String = buildString {
        appendLine(title)
        steps.forEach { appendLine("[${it.status.name.uppercase()}] ${it.name}: ${it.detail}") }
    }.trimEnd()
}

/** Builds a [ConnectionCheck] step by step, publishing each change through [publish]. */
internal class CheckRecorder(private val title: String, private val publish: (ConnectionCheck) -> Unit) {
    private val steps = mutableListOf<CheckStep>()

    init { publish(ConnectionCheck(title, emptyList(), running = true)) }

    fun step(name: String, status: StepStatus, detail: String) {
        val i = steps.indexOfLast { it.name == name && it.status == StepStatus.Running }
        val s = CheckStep(name, status, detail)
        if (i >= 0) steps[i] = s else steps += s
        publish(ConnectionCheck(title, steps.toList(), running = true))
    }

    fun running(name: String, detail: String) = step(name, StepStatus.Running, detail)

    fun finish(status: StepStatus, detail: String): ConnectionCheck {
        steps += CheckStep("Result", status, detail)
        return ConnectionCheck(title, steps.toList(), running = false).also(publish)
    }
}
