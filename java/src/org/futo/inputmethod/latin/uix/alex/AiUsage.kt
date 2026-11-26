package org.futo.inputmethod.latin.uix.alex

import android.content.Context
import org.futo.inputmethod.latin.uix.actions.aicheck.AiCheckClient
import java.util.Calendar

// alex: koliko AI kosta - svi AI pozivi (provera, prevod, tema) se sabiraju po danu i po mesecu.
// Cene $ po milion tokena (ulaz / izlaz), Anthropic cenovnik novembar 2026.
object AiUsage {
    private const val PREFS = "alex_ai_usage"

    private fun price(model: String): Pair<Double, Double> = when {
        model.contains("haiku") -> 0.10 to 0.50
        model.contains("sonnet") -> 2.0 to 10.0
        model.contains("opus") -> 4.0 to 20.0
        else -> 4.0 to 20.0
    }

    /** jednom pri pokretanju tastature (i podesavanja) */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        AiCheckClient.usageListener = AiCheckClient.UsageListener { model, inTok, outTok ->
            record(app, model, inTok, outTok)
        }
    }

    private fun dayKey(): Int { val c = Calendar.getInstance(); return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR) }
    private fun monthKey(): Int { val c = Calendar.getInstance(); return c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH) + 1 }

    @Synchronized
    fun record(ctx: Context, model: String, inTok: Long, outTok: Long) {
        val (pin, pout) = price(model)
        val cost = inTok * pin / 1e6 + outTok * pout / 1e6
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = dayKey(); val m = monthKey()
        val sameDay = p.getInt("day", 0) == d
        val sameMonth = p.getInt("month", 0) == m
        p.edit()
            .putInt("day", d)
            .putInt("day_count", (if (sameDay) p.getInt("day_count", 0) else 0) + 1)
            .putFloat("day_cost", (if (sameDay) p.getFloat("day_cost", 0f) else 0f) + cost.toFloat())
            .putInt("month", m)
            // dan u mesecu od kog se broji (da procena ne racuna dane pre pocetka pracenja)
            .putInt("month_start_day", if (sameMonth) p.getInt("month_start_day", Calendar.getInstance().get(Calendar.DAY_OF_MONTH))
                else Calendar.getInstance().get(Calendar.DAY_OF_MONTH))
            .putInt("month_count", (if (sameMonth) p.getInt("month_count", 0) else 0) + 1)
            .putFloat("month_cost", (if (sameMonth) p.getFloat("month_cost", 0f) else 0f) + cost.toFloat())
            .apply()
    }

    data class Stats(val dayCount: Int, val dayCost: Double, val monthCount: Int, val monthCost: Double,
                     val monthStartDay: Int = 1)

    fun stats(ctx: Context): Stats {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sameDay = p.getInt("day", 0) == dayKey()
        val sameMonth = p.getInt("month", 0) == monthKey()
        return Stats(
            if (sameDay) p.getInt("day_count", 0) else 0,
            if (sameDay) p.getFloat("day_cost", 0f).toDouble() else 0.0,
            if (sameMonth) p.getInt("month_count", 0) else 0,
            if (sameMonth) p.getFloat("month_cost", 0f).toDouble() else 0.0,
            // build 29 nije pamtio pocetak: tada se racuna kao da je pracenje pocelo danas
            if (sameMonth) p.getInt("month_start_day", Calendar.getInstance().get(Calendar.DAY_OF_MONTH)) else 1,
        )
    }

    /** procena za ceo mesec: prosek po danu (od pocetka pracenja u ovom mesecu, racunajuci danas) x 30 */
    fun monthlyEstimate(st: Stats): Double {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val days = (today - st.monthStartDay + 1).coerceAtLeast(1)
        return st.monthCost / days * 30.0
    }

    /** brise brojace (danas i mesec); pocetak pracenja = danas */
    @Synchronized
    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun money(v: Double): String = if (v < 0.01) "$%.4f".format(v) else "$%.2f".format(v)
}
