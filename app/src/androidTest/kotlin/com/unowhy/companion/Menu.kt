package com.unowhy.companion

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.Matchers.allOf

/**
 * Change de mode comme l'utilisateur en thème Cadran : la poignée ouvre le menu, puis l'entrée du mode.
 * Le panneau arrive par un ressort : le toucher sur l'entrée est réessayé tant qu'elle n'est pas assez visible.
 */
fun showMode(mode: Mode) {
    onView(withId(R.id.menu_handle)).perform(click())
    eventually { onView(allOf(withText(mode.label), isDescendantOfA(withId(R.id.menu_items)))).perform(click()) }
}
