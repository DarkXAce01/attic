package io.github.darkxace01.attic

import io.github.darkxace01.attic.data.HelpPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HelpPackTest {
    // What the page's "Send my answers" writes.
    private val sent = """
        {"format":"attic-answers","pack":"1730000000000","from":" Auntie Meena ",
         "faces":[{"print":"scan_17/print_1.png","x":0.4123,"y":0.3,"ids":"4,9,12","name":"Nani"},
                  {"print":"scan_17/print_2.png","x":0.5,"y":0.5,"ids":"5","name":"  "}],
         "photos":[{"print":"scan_17/print_1.png","date":"summer 1987","place":"","memo":"Diwali at the old house"},
                   {"print":"scan_18/print_1.png","date":"","place":"","memo":""}]}
    """.trimIndent()

    @Test
    fun answersComeBackWithBlanksLeftOut() {
        val a = HelpPack.parse(sent)!!
        assertEquals("Auntie Meena", a.from)
        assertEquals(1, a.faces.size)
        assertEquals("Nani", a.faces[0].name)
        assertEquals(listOf(4L, 9L, 12L), a.faces[0].ids)
        assertEquals(0.4123f, a.faces[0].x, 1e-6f)
        assertEquals(1, a.photos.size)
        assertEquals("summer 1987", a.photos[0].date)
        assertNull(a.photos[0].place)
        assertEquals("Diwali at the old house", a.photos[0].memo)
    }

    @Test
    fun anythingElseIsNotAnswers() {
        assertNull(HelpPack.parse("{\"format\":\"attic-backup\"}"))
        assertNull(HelpPack.parse("not json at all"))
        assertNull(HelpPack.parse(""))
    }
}
