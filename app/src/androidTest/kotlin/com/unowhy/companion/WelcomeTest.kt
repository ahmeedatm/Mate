package com.unowhy.companion

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Premier lancement sans ordinateur appairé : écran de bienvenue, « Plus tard », puis un mode qui demande l'ordinateur. */
@RunWith(AndroidJUnit4::class)
class WelcomeTest {

    @Before
    fun premierLancement() {
        // Toutes les préférences effacées : aucun ordinateur, comme une tablette neuve.
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.applicationInfo.dataDir, "shared_prefs").list()?.forEach {
            context.deleteSharedPreferences(it.removeSuffix(".xml"))
        }
    }

    @Test
    fun afficheLaBienvenueSansOrdinateur() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.welcome)).check(matches(isDisplayed()))
            onView(withText(R.string.welcome_title)).check(matches(isDisplayed()))
            onView(withId(R.id.welcome_install_url)).check(matches(withText(BuildConfig.INSTALL_URL)))
            onView(withId(R.id.welcome_manual)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun plusTardPuisDeckRameneALaBienvenue() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.welcome_later)).perform(click())
            onView(withId(R.id.welcome)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.menu_handle)).check(matches(isDisplayed()))

            // Le Deck a besoin d'un ordinateur : il propose d'en connecter un.
            showMode(Mode.DECK)
            onView(withId(R.id.no_computer)).check(matches(isDisplayed()))
            onView(withId(R.id.connect_button)).perform(click())
            onView(withId(R.id.welcome)).check(matches(isDisplayed()))
        }
    }
}
