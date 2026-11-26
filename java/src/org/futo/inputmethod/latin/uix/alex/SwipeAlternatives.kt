package org.futo.inputmethod.latin.uix.alex

// alex: za AI proveru - za svaku svajpovanu rec pamti koje je jos reci swipe ponudio.
// AI tada ne pogadja naslepo, nego bira medju stvarnim kandidatima ("Ste" -> ponudjeno i "Šta").
// Samo u memoriji, poslednjih MAX reci.
object SwipeAlternatives {
    private const val MAX = 150
    private const val MAX_LINES = 40

    private data class Entry(val word: String, val alts: List<String>)

    private val entries = ArrayDeque<Entry>()

    @JvmStatic
    @Synchronized
    fun record(word: String, candidates: List<String>) {
        if (word.isBlank()) return
        val alts = candidates.filter { it.isNotBlank() && !it.equals(word, ignoreCase = true) }
            .distinctBy { it.lowercase() }.take(4)
        if (alts.isEmpty()) return
        entries.addLast(Entry(word, alts))
        while (entries.size > MAX) entries.removeFirst()
    }

    /** korisnik je posle swipe-a tapnuo drugu rec: poslednji zapis za [shown] postaje zapis za [picked] */
    @JvmStatic
    @Synchronized
    fun picked(shown: String, picked: String) {
        val i = entries.indexOfLast { it.word == shown }
        if (i < 0) return
        val e = entries[i]
        entries[i] = Entry(picked, (listOf(shown) + e.alts).filter { !it.equals(picked, ignoreCase = true) }.take(4))
    }

    /** redovi "rec: alt1, alt2" za reci koje se nalaze u tekstu (najnovije prvo), ili null */
    @JvmStatic
    @Synchronized
    fun hintsFor(text: String): String? {
        val words = Regex("[\\p{L}\\d']+").findAll(text).map { it.value }.toSet()
        val seen = HashSet<String>()
        val lines = ArrayList<String>()
        for (e in entries.reversed()) {
            if (e.word !in words || !seen.add(e.word)) continue
            lines.add(e.word + ": " + e.alts.joinToString(", "))
            if (lines.size >= MAX_LINES) break
        }
        return if (lines.isEmpty()) null else lines.reversed().joinToString("\n")
    }
}
