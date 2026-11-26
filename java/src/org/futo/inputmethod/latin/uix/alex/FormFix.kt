package org.futo.inputmethod.latin.uix.alex

import androidx.datastore.preferences.core.booleanPreferencesKey
import org.futo.inputmethod.latin.uix.SettingsKey

// alex: ispravka oblika prethodne reci posle swipe-a sledece.
// Primer: "pravi rezervni" + swipe "kopiju" -> "pravi rezervnu kopiju".
// Facilitator (posle rerangiranja swipe-a) u pozadini proceni da li je drugi oblik prethodne reci
// (bio je medju njenim swipe kandidatima, isti pocetak) bolji uz novu rec, i ostavi predlog ovde.
// InputLogic ga primeni kad se nova rec upise u tekst (sledeci swipe, razmak ili znak).
val FormFixSetting = SettingsKey(booleanPreferencesKey("alex_form_fix"), true)

object FormFix {
    data class Fix(val oldWord: String, val newWord: String, val nextWord: String, val gain: Float)

    @Volatile private var pending: Fix? = null

    fun offer(f: Fix?) { pending = f }

    /** poziva InputLogic posle upisa swipe reci [committed]; vraca ispravku samo za tu rec */
    @JvmStatic
    fun take(committed: String): Fix? {
        val f = pending ?: return null
        pending = null
        return if (f.nextWord == committed) f else null
    }

    /** prvo slovo kao u staroj reci (Rezervni -> Rezervnu) */
    @JvmStatic
    fun matchCase(old: String, new: String): String =
        if (old.isNotEmpty() && old[0].isUpperCase() && new.isNotEmpty())
            new.substring(0, 1).uppercase() + new.substring(1) else new
}
