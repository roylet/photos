package com.roylet.photos.editor

import com.roylet.photos.editor.gl.EditShaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GL cannot run in unit tests, so this checks the shader source statically: every uniform the
 * Kotlin side sets is declared (and used) in the GLSL, and — when `glslangValidator` is on the
 * PATH — that both stages compile as GLSL ES 3.00.
 */
class ShaderSourceTest {
    private val uniformDecl = Regex("""uniform\s+(?:highp\s+|mediump\s+|lowp\s+)?\w+\s+(\w+)(\[\d+])?\s*;""")

    private fun declared(src: String) = uniformDecl.findAll(src).map { it.groupValues[1] }.toSet()

    @Test fun everyKotlinUniformIsDeclared() {
        val vs = declared(EditShaders.VERTEX)
        val fs = declared(EditShaders.FRAGMENT)
        for (u in EditShaders.VERTEX_UNIFORMS) assertTrue("$u missing in vertex shader", u in vs)
        for (u in EditShaders.FRAGMENT_UNIFORMS) assertTrue("$u missing in fragment shader", u in fs)
        // And nothing is declared that Kotlin never sets.
        assertEquals(EditShaders.ALL_UNIFORMS.toSet(), vs + fs)
    }

    @Test fun everyUniformIsUsed() {
        for (u in EditShaders.ALL_UNIFORMS) {
            val src = EditShaders.VERTEX + EditShaders.FRAGMENT
            val uses = Regex("""\b$u\b""").findAll(src).count()
            assertTrue("$u is declared but never read", uses >= 2)
        }
    }

    @Test fun colourBlockIsSelfContained() {
        // The block a Media3 effect would include declares every colour uniform and shade().
        val block = EditShaders.COLOR_PIPELINE
        assertEquals(EditShaders.COLOR_UNIFORMS.toSet(), declared(block))
        assertTrue(block.contains("vec3 shade(vec3 c, vec3 blur, vec3 nb, vec2 outUv)"))
        assertTrue(EditShaders.FRAGMENT.startsWith("#version 300 es"))
        assertTrue(EditShaders.VERTEX.startsWith("#version 300 es"))
        assertTrue("constants must be plain decimals", !Regex("""\d\.\d*[eE][-+]?\d""").containsMatchIn(EditShaders.FRAGMENT))
    }

    @Test fun compilesWithGlslang() {
        val validator = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "glslangValidator") }.firstOrNull { it.canExecute() }
        assumeTrue("glslangValidator not installed", validator != null)
        val dir = File(System.getProperty("java.io.tmpdir"), "edit-shaders").apply { mkdirs() }
        for ((name, src) in listOf("edit.vert" to EditShaders.VERTEX, "edit.frag" to EditShaders.FRAGMENT)) {
            val f = File(dir, name).apply { writeText(src) }
            val proc = ProcessBuilder(validator!!.path, f.path).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            assertTrue("glslangValidator timed out", proc.waitFor(60, TimeUnit.SECONDS))
            assertEquals("$name failed to compile:\n$out", 0, proc.exitValue())
        }
    }
}
