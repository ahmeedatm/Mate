package com.unowhy.companion

import androidx.annotation.StringRes

/** Erreur dont le message est une chaîne de `strings.xml` : [com.unowhy.companion.ui.errorText] l'affiche telle quelle. */
class UserFacingException(@param:StringRes val res: Int, vararg val args: Any) : Exception()
