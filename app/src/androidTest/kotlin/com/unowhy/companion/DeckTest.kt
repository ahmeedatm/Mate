package com.unowhy.companion

import android.content.Context
import android.view.WindowManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Root
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isSelected
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Description
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.anything
import org.hamcrest.Matchers.not
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Deck de bout en bout contre un vrai backend (backend/test/deck-emulateur.ts, deck de test deck-emulateur.json).
 * Le backend tourne sous Linux : il lit le deck et garde les touches perso, mais toucher une touche répond 501.
 */
@RunWith(AndroidJUnit4::class)
class DeckTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun appairer() {
        File(context.applicationInfo.dataDir, "shared_prefs").list()?.forEach {
            context.deleteSharedPreferences(it.removeSuffix(".xml"))
        }
        TestBackend.reset()
        TestBackend.pair(context)
    }

    @Test
    fun afficheLesOngletsEtLesTouchesDuBackend() {
        openDeck().use {
            eventually { onView(tab("Raccourcis")).check(matches(isSelected())) }
            onView(tab("Liens")).check(matches(isDisplayed()))
            onView(tab("Perso")).check(matches(isDisplayed()))
            listOf("Copier", "Coller", "Capture").forEach { onView(tile(it)).check(matches(isDisplayed())) }
        }
    }

    @Test
    fun changeDOngletAuToucherEtAuBalayage() {
        openDeck().use {
            eventually { onView(tile("Copier")).check(matches(isDisplayed())) }
            showTab("Liens")
            onView(tile("Projet")).check(matches(isDisplayed()))
            onView(tile("Copier")).check(doesNotExist())

            onView(withId(R.id.deck_grid)).perform(swipeLeft())
            onView(tab("Perso")).check(matches(isSelected()))
            onView(withId(R.id.deck_status)).check(matches(withText(R.string.deck_edit_hint)))

            onView(withId(R.id.deck_grid)).perform(swipeRight())
            onView(tab("Liens")).check(matches(isSelected()))
            onView(withId(R.id.deck_grid)).perform(swipeRight())
            onView(tab("Raccourcis")).check(matches(isSelected()))
            onView(tile("Copier")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun toucherUneToucheLEnvoieAuBackendEtAfficheSonRefus() {
        openDeck().use {
            eventually { onView(tile("Copier")).check(matches(isDisplayed())) }
            onView(tile("Copier")).perform(click())
            eventually { assertEquals(listOf("0-0"), TestBackend.runs()) }
            // Linux : 501, message de l'ordinateur affiché tel quel, en français.
            eventually { onView(withText("Pilotage disponible sur macOS et Windows seulement")).inRoot(isToast()).check(matches(isDisplayed())) }
            onView(withId(R.id.deck_grid)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun programmeUneTouchePersoEtLaRetrouveApresRechargement() {
        openDeck().use {
            showTab("Perso")
            onView(withId(R.id.deck_status)).check(matches(withText(R.string.deck_edit_hint)))
            onData(anything()).inAdapterView(withId(R.id.deck_grid)).atPosition(0).perform(click())
            onView(withId(R.id.editor_title)).check(matches(withText(context.getString(R.string.key_title, 1))))

            onView(withId(R.id.editor_label)).perform(replaceText("Bonjour"))
            onView(allOf(withText(R.string.step_text), isDescendantOfA(withId(R.id.editor_types)))).perform(scrollTo(), click())
            onView(allOf(isAssignableFrom(EditText::class.java), isDescendantOfA(withId(R.id.editor_form)))).perform(replaceText("Salut"))
            onView(allOf(withText(R.string.key_add), isDescendantOfA(withId(R.id.editor_form)))).perform(scrollTo(), click())
            onView(allOf(withText(context.getString(R.string.key_step_line, 1, context.getString(R.string.sum_text, "Salut"))), isDescendantOfA(withId(R.id.editor_steps))))
                .check(matches(isDisplayed()))
            onView(withId(R.id.editor_save)).perform(click())

            eventually { onView(tile("Bonjour")).check(matches(isDisplayed())) }
            onView(withId(R.id.deck_editor)).check(matches(not(isDisplayed())))
            val saved = TestBackend.persoKey(0)!!
            assertEquals("Bonjour", saved.getString("label"))
            assertEquals("Salut", saved.getJSONArray("steps").getJSONObject(0).getString("text"))
        }
        // Appli relancée : la touche vient du backend, pas de la mémoire de l'écran.
        openDeck().use {
            showTab("Perso")
            onView(tile("Bonjour")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun perteDuBackendPuisRetour() {
        openDeck().use {
            eventually { onView(tile("Copier")).check(matches(isDisplayed())) }
            eventually(20_000) { onView(withId(R.id.status)).check(matches(withContentDescription(R.string.online))) }

            TestBackend.stop()
            eventually(20_000) { onView(withId(R.id.status)).check(matches(withContentDescription(R.string.offline))) }
            onView(withId(R.id.deck_refresh)).perform(click())
            val unreachable = context.getString(R.string.error_unreachable, "Ordi de test")
            eventually { onView(withId(R.id.deck_status)).check(matches(allOf(isDisplayed(), withText(unreachable)))) }

            TestBackend.start()
            eventually(30_000) { onView(withId(R.id.status)).check(matches(withContentDescription(R.string.online))) }
            onView(withId(R.id.deck_refresh)).perform(click())
            eventually { onView(withId(R.id.deck_status)).check(matches(not(isDisplayed()))) }
            onView(tile("Copier")).check(matches(isDisplayed()))
        }
    }

    private fun openDeck(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java).also {
        onView(withContentDescription(R.string.mode_deck)).perform(click())
    }

    /**
     * Touche un onglet jusqu'à ce qu'il soit choisi : sur l'émulateur lent, un toucher pendant le chargement peut
     * durer assez pour devenir un appui long (déplacement de l'onglet), et toucher deux fois un onglet ne change rien.
     */
    private fun showTab(title: String) = eventually {
        onView(tab(title)).perform(click())
        onView(tab(title)).check(matches(isSelected()))
    }

    private fun tab(title: String) = allOf(withText(title), isDescendantOfA(withId(R.id.deck_tabs)))

    private fun tile(label: String) = allOf(withId(R.id.tile_label), withText(label))

    /** Fenêtre d'un Toast (Android 10 : affiché par l'appli elle-même). */
    private fun isToast() = object : TypeSafeMatcher<Root>() {
        override fun describeTo(description: Description) {
            description.appendText("est un Toast")
        }

        @Suppress("DEPRECATION")
        override fun matchesSafely(root: Root) = root.windowLayoutParams.get().type == WindowManager.LayoutParams.TYPE_TOAST
    }
}
