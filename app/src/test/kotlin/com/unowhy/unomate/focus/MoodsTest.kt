package com.unowhy.unomate.focus

import com.unowhy.unomate.focus.Moods.Mood
import com.unowhy.unomate.focus.Moods.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodsTest {

    @Test
    fun `stress et dispersion font d'abord respirer`() {
        assertEquals(Plan.Breathe(Breathing.Pattern.COHERENCE, 5, NoisePlayer.Kind.STORM), Moods.planFor(Mood.STRESSED))
        assertEquals(Plan.Breathe(Breathing.Pattern.BOX, 3, NoisePlayer.Kind.FOREST), Moods.planFor(Mood.SCATTERED))
    }

    @Test
    fun `fatigue et motivation reglent le pomodoro`() {
        assertEquals(FocusPresets.Preset(15, 3), (Moods.planFor(Mood.TIRED) as Plan.Work).preset)
        assertEquals(FocusPresets.Preset(50, 10), (Moods.planFor(Mood.MOTIVATED) as Plan.Work).preset)
    }

    @Test
    fun `chaque proposition tient dans les bornes de focus`() {
        for (mood in Mood.entries) {
            when (val plan = Moods.planFor(mood)) {
                is Plan.Breathe -> assertTrue(plan.minutes in Breathing.DURATIONS)
                is Plan.Work -> assertEquals(plan.preset, FocusPresets.bounded(plan.preset.focus, plan.preset.pause))
            }
        }
    }
}
