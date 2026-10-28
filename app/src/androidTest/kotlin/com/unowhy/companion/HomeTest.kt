package com.unowhy.companion

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unowhy.companion.focus.FocusTimer
import com.unowhy.companion.focus.Water
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Accueil avec un ordinateur appairé mais éteint : pas d'écran de bienvenue, et les widgets propres à la tablette
 * (Focus, eau du héros) marchent sans lui.
 */
@RunWith(AndroidJUnit4::class)
class HomeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun ordinateurEteint() {
        // clear() en mémoire : un apply() du test précédent n'est peut-être pas encore sur le disque.
        val files = File(context.applicationInfo.dataDir, "shared_prefs").list().orEmpty().map { it.removeSuffix(".xml") }
        (files + "companion" + "water").distinct().forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        // Port 9 (discard) : personne n'écoute, la connexion est refusée tout de suite.
        Computers.save(context, Computer("ordi-test", "Bureau", "http://127.0.0.1:9", "jeton", "windows"), makeActive = true)
    }

    @After
    fun arreterLeChrono() {
        FocusTimer.reset()
    }

    private fun glassesLabel(count: Int) = context.resources.getQuantityString(R.plurals.hero_glasses, count, Water.state(context).goal)

    @Test
    fun accueilSansBienvenueAvecLesWidgetsDeLaTablette() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.welcome)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.home_grid)).check(matches(isDisplayed()))
            onView(withId(R.id.widget_focus)).check(matches(isDisplayed()))
            onView(withId(R.id.hero_water)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun verreDEauEtChronoDepuisLAccueil() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.hero_glasses_label)).check(matches(withText(glassesLabel(0))))
            onView(withId(R.id.hero_water)).perform(click())
            onView(withId(R.id.water_today_plus)).perform(click())
            assertEquals(1, Water.state(context).glasses)
            pressBack()
            eventually { onView(withId(R.id.hero_glasses_label)).check(matches(withText(glassesLabel(1)))) }

            onView(withId(R.id.widget_focus_toggle)).perform(click())
            assertTrue(FocusTimer.state.running)
        }
    }
}
