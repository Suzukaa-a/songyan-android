package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.EngineOptionDescriptor
import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.keyboard.engine.EngineOptionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The long-press menu lists every switch and option group a schema declares, as rime-bopomofo does. */
class MenuSwitchesTest {
    private fun state(
        name: String,
        kind: EngineOptionKind,
        value: Boolean = false,
        group: List<String> = emptyList(),
        label: String? = null,
        states: List<String> = emptyList(),
    ) = EngineOptionState(
        EngineOptionDescriptor(
            "res",
            "bopomofo",
            name,
            kind,
            choiceGroup = group,
            choiceLabel = label,
            stateLabels = states,
        ),
        value,
    )

    @Test
    fun fullShapeAndTheScriptGroupAreListedBesideDeclaredSwitches() {
        val group = listOf("zh_hant", "zh_hans", "zh_hant_hk", "zh_hant_tw")
        val labels = listOf("傳統漢字", "简化字", "香港字形", "臺灣字形")
        val options =
            listOf(
                state("full_shape", EngineOptionKind.FULL_SHAPE, states = listOf("半角", "全角")),
            ) +
                group.mapIndexed { i, name ->
                    state(name, EngineOptionKind.TRADITIONAL, value = i == 0, group = group, label = labels[i])
                } +
                state("ascii_punct", EngineOptionKind.DECLARED, states = listOf("。，", "．，"))

        val entries = MenuSwitches.entries(options) { "fallback" }

        assertEquals(3, entries.size)
        assertEquals("半角", (entries[0] as KeyboardSwitcherPanel.SwitchEntry.Toggle).label)
        val listed = (entries[1] as KeyboardSwitcherPanel.SwitchEntry.Group).options
        assertEquals(labels, listed.map { it.label })
        assertTrue(listed.first().selected)
        assertEquals("。，", (entries[2] as KeyboardSwitcherPanel.SwitchEntry.Toggle).label)
    }

    @Test
    fun aSwitchWithoutStateLabelsIsNamedByItsKind() {
        val entries = MenuSwitches.entries(listOf(state("full_shape", EngineOptionKind.FULL_SHAPE))) { "Full width" }
        assertEquals("Full width", (entries.single() as KeyboardSwitcherPanel.SwitchEntry.Toggle).label)
    }
}
