package org.futo.inputmethod.latin.xlm;

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.engine.general.OnGetSuggestedWordsCallbackWithInputStyle
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.keyboard.KeyboardSwitcher
import org.futo.inputmethod.latin.BinaryDictionary
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.Dictionary
import org.futo.inputmethod.latin.DictionaryFacilitator
import org.futo.inputmethod.latin.NgramContext
import org.futo.inputmethod.latin.Suggest
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.SuggestionBlacklist
import org.futo.inputmethod.latin.common.ComposedData
import org.futo.inputmethod.latin.inputlogic.InputLogic
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.USE_TRANSFORMER_FINETUNING
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.utils.SuggestionResults
import kotlin.math.ceil


val AutocorrectThresholdSetting = SettingsKey(
    floatPreferencesKey("lm_autocorrect_threshold"),
    4.0f
)

val BinaryDictTransformerWeightSetting = SettingsKey(
    floatPreferencesKey("binary_dict_result_weight"),
    3.4f
)

// alex: srpski QWERTZ (sa š đ č ć ž) nije "qwerty", pa je transformer za kucanje bio iskljucen
val AllowTransformerOnNonQWERTYLayouts = SettingsKey(
    booleanPreferencesKey("allow_transformer_lm_on_non_qwerty"),
    true
)

// alex: koliko transformer utice na redosled swipe kandidata (0 = iskljuceno)
val SwipeLmWeightSetting = SettingsKey(
    floatPreferencesKey("alex_swipe_lm_weight"),
    0.3f
)

internal fun SuggestedWordInfo.add(other: SuggestedWordInfo): SuggestedWordInfo {
    assert(mWord == other.mWord)

    val result = SuggestedWordInfo(
        mWord,
        mPrevWordsContext,
        (mScore.coerceAtLeast(0).toLong() + other.mScore.coerceAtLeast(0).toLong())
            .coerceAtMost(
                Int.MAX_VALUE.toLong()
            ).toInt(),
        SuggestedWordInfo.KIND_WHITELIST or SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION,
        mSourceDict ?: other.mSourceDict,
        0,
        0
    )

    result.mOriginatesFromTransformerLM = mOriginatesFromTransformerLM || other.mOriginatesFromTransformerLM

    return result
}


internal fun SuggestedWordInfo.scoreAtLeast(other: SuggestedWordInfo): SuggestedWordInfo {
    val result = SuggestedWordInfo(
        mWord,
        mPrevWordsContext,
        mScore.coerceAtLeast(other.mScore + 1),
        SuggestedWordInfo.KIND_WHITELIST or SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION,
        mSourceDict,
        0,
        0
    )

    return result
}

internal fun SuggestedWordInfo.scoreAtMost(other: SuggestedWordInfo): SuggestedWordInfo {
    val result = SuggestedWordInfo(
        mWord,
        mPrevWordsContext,
        mScore.coerceAtMost(other.mScore - 1),
        mKindAndFlags,
        mSourceDict,
        0,
        0
    )

    return result
}

private fun levenshteinDistance(s1: String, s2: String): Int {
    val len1 = s1.length
    val len2 = s2.length

    val dist = Array(len1 + 1) { IntArray(len2 + 1) }

    for (i in 0..len1) {
        dist[i][0] = i
    }
    for (j in 0..len2) {
        dist[0][j] = j
    }

    for (j in 1..len2) {
        for (i in 1..len1) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            dist[i][j] = minOf(
                dist[i - 1][j] + 1,
                dist[i][j - 1] + 1,
                dist[i - 1][j - 1] + cost
            )
        }
    }

    return dist[len1][len2]
}


// alex: podesavanje swipe + transformer
private const val SWIPE_RESCORE_TOP_N = 8
private const val SWIPE_LM_SPAN_NATS = 10.0f  // razlika log-verovatnoce koja daje punu kaznu
private const val SWIPE_SCORE_SPAN = 4000f    // razlika skora dekodera koja daje ocenu putanje 0 (najbolji = 1)
// alex: reci koje predvidi transformer, a odgovaraju putanji swipe-a
private const val SWIPE_PREDICTED_MAX = 4
private const val SWIPE_PREDICTED_FIT = 0.8f  // ocena swipe-a za takvu rec (najbolji swipe kandidat = 1.0)
private const val SWIPE_FIT_START = 1.1f      // udaljenost u sirinama tastera: pocetak putanje do prvog slova
private const val SWIPE_FIT_END = 0.8f        // kraj putanje do poslednjeg slova (strozije: je/jer)
private const val SWIPE_FIT_MID = 0.7f        // putanja mora proci blizu svakog slova izmedju

public class LanguageModelFacilitator(
    val context: Context,
    val inputLogic: InputLogic,
    val dictionaryFacilitator: DictionaryFacilitator,
    val settings: Settings,
    val keyboardSwitcher: KeyboardSwitcher,
    val lifecycleScope: LifecycleCoroutineScope,
    val suggestionBlacklist: SuggestionBlacklist,
    val suggestedWordsCallback: OnGetSuggestedWordsCallbackWithInputStyle
) {
    private val TAG = "LanguageModelFacilitator"
    private val userDictionary = UserDictionaryObserver(context)

    private var languageModel: LanguageModel? = null
    data class PredictionInputValues(
        val composedData: ComposedData,
        val ngramContext: NgramContext,
        val inputStyle: Int,
        val sequenceId: Int
    )
    private val sharedFlow = MutableSharedFlow<PredictionInputValues>(replay = 0, extraBufferCapacity = 1)

    private var currentSequenceId = 0
    private val sequenceIdFinishedFlow = MutableSharedFlow<Pair<PredictionInputValues, SuggestedWords?>>(replay = 1, extraBufferCapacity = 1)

    @OptIn(ExperimentalCoroutinesApi::class)
    val languageModelScope = LanguageModelScope

    private var numConsecutiveTimeouts = 0
    private var transformerDisabled = false
    @Volatile private var timeoutPauseUntil = 0L

    fun reportTimeout() {
        if(shouldPassThroughToLegacy()) return

        if(BuildConfig.DEBUG) Log.d(TAG, "Failed to complete prediction within the time!")
        numConsecutiveTimeouts += 1
        if(numConsecutiveTimeouts > 5) {
            // alex: ranije iskljuceno do sledeceg polja; sada samo 30 s, i ne vazi za swipe
            timeoutPauseUntil = System.currentTimeMillis() + 30_000L
            numConsecutiveTimeouts = 0
            if(BuildConfig.DEBUG) Log.w(TAG, "Temporarily disabling transformer due to continuous timeouts")
        }
    }

    private var skipLanguage: String? = null
    // alex: izdvojeno ucitavanje modela da ga koristi i swipe
    private suspend fun prepareLanguageModel(): LanguageModel? {
        if(transformerDisabled) return null

        val locale = dictionaryFacilitator.primaryLocale ?: return null
        if ((languageModel == null && locale.language != skipLanguage) || (languageModel != null && languageModel?.locale?.language != locale.language)) {
            skipLanguage = null
            if(BuildConfig.DEBUG) Log.d(TAG, "Calling closeInternalLocked on model due to seeming locale change")
            languageModel?.closeInternalLocked()
            languageModel = null

            // TODO: Cache value so we're not hitting this repeatedly
            val options = ModelPaths.getModelOptions(context)
            val model = options[locale.language]
            if (model != null) {
                languageModel = LanguageModel(context, lifecycleScope, model, locale)
            } else {
                if(BuildConfig.DEBUG) Log.d(TAG, "no model for ${locale.language}")
                skipLanguage = locale.language
                return null
            }
        }

        if(dictionaryFacilitator.mostConfidentLocale != languageModel?.locale) return null
        return languageModel
    }

    private suspend fun runLanguageModel(values: PredictionInputValues): ArrayList<SuggestedWordInfo>? {
        if(prepareLanguageModel() == null) return null
        val locale = dictionaryFacilitator.primaryLocale ?: return null

        val keyboard = keyboardSwitcher.keyboard ?: return null

        val proximityInfoHandle = keyboard.proximityInfo.nativeProximityInfo

        val autocorrectThreshold = context.getSetting(AutocorrectThresholdSetting)

        try {
            return languageModel?.getSuggestions(
                values.composedData,
                values.ngramContext,
                proximityInfoHandle,
                autocorrectThreshold,
                userDictionary.getWords(listOf(locale)).map { it.word },
                arrayOf()
            )
        }catch (e: ModelLoadingException) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Unable to load Transformer model for ${locale.getDisplayLanguage(locale)}, it may be corrupted or unsupported.",
                    Toast.LENGTH_LONG
                ).show()
                transformerDisabled = true
                e.printStackTrace()
            }
            return null
        }
    }

    suspend fun getLanguageModelSuggestions(values: PredictionInputValues): ArrayList<SuggestedWordInfo>? {
        if(values.composedData.mTypedWord.length > BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH-1)
            return null

        val lmSuggestions = runLanguageModel(values)
        return lmSuggestions
    }

    fun processAndMergeSuggestions(
        values: PredictionInputValues,
        suggestedWordsDict: SuggestedWords,
        lmSuggestions: ArrayList<SuggestedWordInfo>
    ): SuggestedWords? {
        var transformerWeight = context.getSetting(BinaryDictTransformerWeightSetting)
        if(dictionaryFacilitator.locales.size > 1) transformerWeight = 1.0f

        val suggestionResults = SuggestionResults(
            14, values.ngramContext.isBeginningOfSentenceContext, false)


        val reweightedSuggestions = lmSuggestions.mapIndexedNotNull { i, it ->
            if(transformerWeight == Float.NEGATIVE_INFINITY) { null } else {
                SuggestedWordInfo(
                    it.mWord,
                    it.mPrevWordsContext,
                    (it.mScore.toFloat() * transformerWeight).toLong().coerceAtMost(Int.MAX_VALUE.toLong() - lmSuggestions.size)
                        .toInt() - i + (lmSuggestions.size - 1),
                    it.mKindAndFlags,
                    it.mSourceDict,
                    it.mIndexOfTouchPointOfSecondWord,
                    it.mAutoCommitFirstWordConfidence
                ).apply {
                    this.mOriginatesFromTransformerLM = true
                }
            }
        }

        val maxWord = reweightedSuggestions.maxByOrNull { it.mScore }

        val suggestedWordsDictList = suggestedWordsDict.mSuggestedWordInfoList.filter {
            suggestionBlacklist.isSuggestedWordOk(it)
        }.toMutableList()

        var maxWordDict = suggestedWordsDictList.maxByOrNull {
            if(it == suggestedWordsDict.typedWordInfo
                || it.isKindOf(SuggestedWordInfo.KIND_EMOJI_SUGGESTION)) Int.MIN_VALUE else it.mScore
        }

        val maxNonWhitelistWordDict = suggestedWordsDictList.maxByOrNull {
            if(it == suggestedWordsDict.typedWordInfo
                || it.isKindOf(SuggestedWordInfo.KIND_EMOJI_SUGGESTION)
                || (it.isKindOf(SuggestedWordInfo.KIND_WHITELIST) && it.mSourceDict?.mDictType == "main")) Int.MIN_VALUE else it.mScore
        }

        // English language has some shortcuts, e.g. "bot" -> "not",    "hid" -> "his"
        // These are common misspellings and usually useful corrections, but if the language model
        // believes the original word fits better in the context we should skip the shortcut,
        // else it's impossible to type these words without manually skipping correction in
        // suggestion bar
        if(maxWordDict != null && maxNonWhitelistWordDict != null && maxNonWhitelistWordDict != maxWordDict && maxNonWhitelistWordDict.mWord == maxWord?.mWord) {
            val idx = suggestedWordsDictList.indexOf(maxWordDict)
            suggestedWordsDictList.remove(maxWordDict)
            suggestedWordsDictList.add(idx, maxWordDict.scoreAtMost(maxNonWhitelistWordDict))
            maxWordDict = maxNonWhitelistWordDict
        }

        val bothAlgorithmsCameToSameConclusion = maxWordDict?.mWord == maxWord?.mWord

        var autocorrectWord: SuggestedWordInfo? = null
        val filtered = mutableListOf<SuggestedWordInfo>()
        if(bothAlgorithmsCameToSameConclusion && maxWord != null && maxWordDict != null){
            if(BuildConfig.DEBUG) Log.d(TAG, "both algorithms came to same conclusion, autocorrect to ${maxWord.mWord}")
            // We can be pretty confident about autocorrecting this
            val clone = maxWord.add(maxWordDict)
            autocorrectWord = clone
            suggestionResults.add(clone)
            filtered.add(maxWordDict)
            filtered.add(maxWord)
        }
        // In some cases the LM will predict an uppercased word but dictionary predicts lowercased,
        // we should prefer the lowercase version to reduce automatically capitalizing which can be
        // annoying
        val bothAlgorithmsCameToSameConclusionButLowerCased = maxWordDict?.mWord == maxWord?.mWord?.lowercase()
        if(bothAlgorithmsCameToSameConclusionButLowerCased && maxWord != null && maxWordDict != null) {
            if(BuildConfig.DEBUG) Log.d(TAG, "both algorithms came to same conclusion but lowercased, autocorrect to ${maxWord.mWord}")
            val clone = maxWordDict.scoreAtLeast(maxWord)
            autocorrectWord = clone
            suggestionResults.add(clone)
            filtered.add(maxWordDict)
        }

        if(transformerWeight <= 0.0f) {
            if(suggestedWordsDictList.isEmpty()) {
                transformerWeight = 1.0f
            }
        }

        // Add reweightedSuggestions, with space replacement logic. It can replace one of the LM
        // suggestions if the top dictionary result has a space, based on heuristics about the
        // relative quality of the LM suggestion
        val spaceReplacementPossible = maxWordDict != null && maxWordDict.word.count { it == ' ' } == 1
        var spaceReplacementPerformed = false
        for(i in 0 until reweightedSuggestions.size) {
            val word = reweightedSuggestions[i]
            if(filtered.contains(word)) continue

            if(!spaceReplacementPerformed && spaceReplacementPossible && (
                        // If the dict score is high enough, allow the space suggestion
                        ((maxWordDict.mScore) > (word.mScore / 3))
                                // Most LM-generated dashed suggestions are distractions, so accept the space suggestion
                                || (word.word.contains('-'))
                                // If the typed word is much longer than the transformer word, just accept the space suggestion
                                || (values.composedData.mTypedWord.length > ceil(word.word.length * 3.0 / 2.0))
                        )
            ) {
                val clone = maxWordDict.scoreAtLeast(word)
                suggestionResults.add(clone)
                spaceReplacementPerformed = true
                continue
            }

            suggestionResults.add(word)
        }

        if(maxWordDict?.mSourceDict?.mDictType == Dictionary.TYPE_USER_HISTORY
            && maxWordDict.mScore > 100
            && maxWord != null
            && (maxWordDict.mWord == values.composedData.mTypedWord || maxWordDict.mWord.length > 1)
        ) {
            if(BuildConfig.DEBUG) Log.d(TAG, "type user history found")
            val clone = maxWordDict.scoreAtLeast(maxWord)
            suggestionResults.add(clone)
        }

        suggestionResults.mRawSuggestions?.addAll(reweightedSuggestions.filter { !filtered.contains(it) })
        if(transformerWeight != Float.POSITIVE_INFINITY) {
            suggestedWordsDictList?.let { words ->
                suggestionResults.addAll(words.filter {
                    it != suggestedWordsDict.typedWordInfo && !filtered.contains(
                        it
                    )
                })
            }
        }

        val settingsValues = settings.current ?: return null
        val locale = dictionaryFacilitator.primaryLocale ?: return null
        val wordComposer = inputLogic.mWordComposer ?: return null

        val suggestedWords = Suggest.obtainNonBatchedInputSuggestedWords(
            wordComposer,
            values.inputStyle,
            settingsValues.mAutoCorrectionEnabledPerUserSettings,
            -1,
            locale,
            suggestionResults,
            settingsValues.mAutoCorrectionThreshold,
            settingsValues.mIsNumberRowEnabled
        )


        if(BuildConfig.DEBUG) {
            val dmpw: (SuggestedWordInfo?) -> String = { v -> v?.let { "${it.mWord}:${it.mScore}:${it.mKindAndFlags}:${it.mSourceDict?.mDictType}" } ?: "[null]" }
            val dmp: (List<SuggestedWordInfo>?) -> String = { v -> v?.joinToString { dmpw(it) } ?: "[null]" }
            Log.d(TAG, "process update suggestion strip:\n" +
                    "raw lm results: ${dmp(lmSuggestions)}\n" +
                    "reweighted lm results: ${dmp(reweightedSuggestions)}\n" +
                    "raw dict results: ${dmp(suggestedWordsDict?.mSuggestedWordInfoList)}}\n" +
                    "filtered dict results: ${dmp(suggestedWordsDictList)}}\n" +
                    "------------\n" +
                    "max word lm: ${dmpw(maxWord)}}\n" +
                    "max word dict: ${dmpw(maxWordDict)}}\n"
            )
        }


        return suggestedWords
    }

    // alex: predlozi sledece reci za red ispod predloga
    suspend fun predictNextWords(rawContext: String?, afterWord: String?): List<String> {
        if(shouldPassThroughToLegacy()) return emptyList()
        val lm = try { prepareLanguageModel() } catch(e: ModelLoadingException) { null } ?: return emptyList()
        val prox = keyboardSwitcher.keyboard?.proximityInfo?.nativeProximityInfo ?: return emptyList()
        val ngram = if(rawContext == null) inputLogic.getNgramContextFromNthPreviousWordForSuggestion(
            settings.current.mSpacingAndPunctuations, 2
        ) else null
        return lm.predictNextWords(ngram, rawContext, afterWord, prox)
    }

    // alex: swipe kandidate iz recnika ponovo rangira transformer (verovatnoca reci u kontekstu).
    // Konacna ocena = ocena swipe-a (0..1 unutar prvih N) + tezina * ocena modela (-1..0).
    // alex: kad je swipe log ukljucen, GeneralIME ovde postavi bafer i rescoreSwipe upisuje sta radi
    @Volatile var swipeDebug: StringBuilder? = null
    fun modelLabel(): String = languageModel?.modelInfoLoader?.let { "${it.name} (${it.path.name})" } ?: "nije ucitan"
    private fun dbg(s: String) { swipeDebug?.append(s)?.append('\n') }

    suspend fun rescoreSwipe(dict: SuggestedWords): SuggestedWords? {
        if(shouldSkipForSwipe()) { dbg("preskoceno: transformer nije za ovaj jezik/raspored"); return null }
        if(System.currentTimeMillis() < timeoutPauseUntil) dbg("napomena: kucanje je kasnilo, swipe ipak koristi transformer")
        val list = dict.mSuggestedWordInfoList
        if(list.isEmpty()) { dbg("preskoceno: dekoder bez kandidata"); return null }
        val weight = context.getSetting(SwipeLmWeightSetting)
        if(weight <= 0.01f) { dbg("preskoceno: jacina 0"); return null }
        // putanju uzeti odmah, dok je composer jos drzi
        val path = currentSwipePath()
        if(path == null) dbg("putanja: nema (ubacivanje predvidjenih reci iskljuceno)")
        else if(swipeDebug != null) dbg("putanja: ${path.n} tacaka, od ${keyNear(path, 0)} do ${keyNear(path, path.n - 1)}")
        val lm = try { prepareLanguageModel() } catch(e: ModelLoadingException) { null }
        if(lm == null) { dbg("preskoceno: transformer model nije ucitan"); return null }

        val ngramContext = inputLogic.getNgramContextFromNthPreviousWordForSuggestion(
            settings.current.mSpacingAndPunctuations, 2
        )
        val top0 = list.take(SWIPE_RESCORE_TOP_N)
        if(swipeDebug != null) {
            dbg("kontekst: [${ngramContext.extractPrevWordsContext()}]")
            dbg("dekoder: " + list.take(12).joinToString(" ") { "${it.mWord}(${it.mScore})" })
        }

        // alex: reci koje transformer ocekuje, a putanja im odgovara, dodaju se medju kandidate
        val extra = if(path == null) emptyList() else try {
            predictedSwipeWords(lm, ngramContext, path, top0.map { it.mWord.lowercase() }.toSet())
        } catch(e: Exception) {
            if(e is CancellationException) throw e
            Log.e(TAG, "predicted swipe words failed", e)
            emptyList()
        }
        val first = top0.first()
        val top = top0 + extra.map {
            SuggestedWordInfo(it, "", first.mScore, SuggestedWordInfo.KIND_CORRECTION, first.mSourceDict,
                SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE).apply {
                // ravna linija u traci: rec je ubacio transformer, ne swipe dekoder
                mOriginatesFromTransformerLM = true
            }
        }
        if(top.size < 2) { dbg("preskoceno: samo jedan kandidat"); return null }

        val lp = lm.scoreWords(ngramContext, top.map { it.mWord })
        if(lp == null || lp.size != top.size) { dbg("preskoceno: scoreWords nije uspeo"); return null }

        val sMax = top0.maxOf { it.mScore }
        val lpMax = lp.max()

        val reranked = top.mapIndexed { i, w ->
            // alex: po stvarnoj razlici od najboljeg (ranije min-max, pa je poslednji uvek dobijao 0)
            val d = if(i >= top0.size) SWIPE_PREDICTED_FIT
                    else (1f - (sMax - w.mScore) / SWIPE_SCORE_SPAN).coerceIn(0f, 1f)
            val l = ((lp[i] - lpMax) / SWIPE_LM_SPAN_NATS).coerceAtLeast(-1.0f)
            if(BuildConfig.DEBUG) Log.d(TAG, "swipe rescore [${w.mWord}] dict=$d lm=${lp[i]} -> ${d + weight * l}")
            if(swipeDebug != null) dbg("  ${if(i >= top0.size) "+" else " "}${w.mWord}: putanja=%.2f lm=%.1f -> %.2f".format(d, lp[i], d + weight * l))
            w to (d + weight * l)
        }.sortedByDescending { it.second }.map { it.first }

        if(reranked.first() == top.first() && reranked == top) { dbg("redosled: bez promene"); return null }
        dbg("redosled: " + reranked.joinToString(" ") { it.mWord })

        val newList = ArrayList<SuggestedWordInfo>(reranked + list.drop(top0.size))
        return SuggestedWords(
            newList,
            dict.mRawSuggestions,
            newList[0],
            true,
            false,
            false,
            dict.mInputStyle,
            dict.mSequenceNumber
        )
    }

    private class SwipePath(val xs: IntArray, val ys: IntArray, val n: Int)

    private fun currentSwipePath(): SwipePath? {
        val ptrs = inputLogic.mWordComposer.inputPointers ?: return null
        val n = ptrs.pointerSize
        if(n < 2) return null
        return SwipePath(ptrs.xCoordinates.copyOf(n), ptrs.yCoordinates.copyOf(n), n)
    }

    // alex: reci koje transformer ocekuje posle konteksta, a putanja swipe-a im odgovara
    // (pocetak, kraj i sva slova redom blizu putanje). Tako swipe nadje "što" i kad krene sa "p".
    private suspend fun predictedSwipeWords(
        lm: LanguageModel, ngram: NgramContext, path: SwipePath, have: Set<String>
    ): List<String> {
        val kb = keyboardSwitcher.keyboard ?: return emptyList()
        val prox = kb.proximityInfo?.nativeProximityInfo ?: return emptyList()
        val predicted = lm.predictNextWords(ngram, null, null, prox)
        val ok = ArrayList<String>()
        val why = ArrayList<String>()
        for(w in predicted) {
            val r = when {
                w.length < 2 -> "kratka"
                w.lowercase() in have -> "vec_ima"
                !dictionaryFacilitator.isValidSuggestionWord(w) -> "nije_u_recniku"
                else -> fitReason(kb, w, path) ?: "OK"
            }
            if(r == "OK" && ok.size < SWIPE_PREDICTED_MAX) ok.add(w)
            if(swipeDebug != null) why.add("$w:$r")
        }
        if(swipeDebug != null) dbg("transformer predvidja: " + why.joinToString(" "))
        return ok
    }

    private fun keyNear(p: SwipePath, j: Int): String {
        val kb = keyboardSwitcher.keyboard ?: return "?"
        var best = "?"; var bd = Float.MAX_VALUE
        for(k in kb.sortedKeys) {
            if(k.code <= 0) continue
            val dx = p.xs[j] - (k.x + k.width / 2f); val dy = p.ys[j] - (k.y + k.height / 2f)
            val d = dx * dx + dy * dy
            if(d < bd) { bd = d; best = String(Character.toChars(k.code)) }
        }
        return best
    }

    private fun keyCenter(kb: Keyboard, c: Int): FloatArray? {
        var key = kb.getKey(c) ?: kb.getKey(Character.toUpperCase(c))
        if(key == null) {
            // slovo sa kvacicom bez svog tastera -> osnovno slovo (š -> s, đ -> d)
            val base = if(c == 'đ'.code) 'd'.code else java.text.Normalizer
                .normalize(String(Character.toChars(c)), java.text.Normalizer.Form.NFD)[0].code
            key = kb.getKey(base)
        }
        key ?: return null
        return floatArrayOf(key.x + key.width / 2f, key.y + key.height / 2f)
    }

    private fun fitsSwipePath(kb: Keyboard, word: String, p: SwipePath): Boolean = fitReason(kb, word, p) == null

    // null = rec odgovara putanji; inace razlog odbijanja (za log)
    private fun fitReason(kb: Keyboard, word: String, p: SwipePath): String? {
        val kw = kb.mMostCommonKeyWidth.toFloat().coerceAtLeast(1f)
        val kh = kb.mMostCommonKeyHeight.toFloat().coerceAtLeast(1f)
        val centers = ArrayList<FloatArray>()
        val s = word.lowercase()
        var i = 0
        var prev = -1
        while(i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if(!Character.isLetter(cp) || cp == prev) continue // dupla slova swipe ne vidi
            prev = cp
            centers.add(keyCenter(kb, cp) ?: return "nema_tastera")
        }
        if(centers.size < 2) return "kratka"
        fun dist(j: Int, c: FloatArray): Float {
            val dx = (p.xs[j] - c[0]) / kw
            val dy = (p.ys[j] - c[1]) / kh
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        dist(0, centers.first()).let { if(it > SWIPE_FIT_START) return "pocetak%.1f".format(it) }
        dist(p.n - 1, centers.last()).let { if(it > SWIPE_FIT_END) return "kraj%.1f".format(it) }
        var j = 0
        for(k in 1 until centers.size - 1) {
            while(j < p.n && dist(j, centers[k]) > SWIPE_FIT_MID) j++
            if(j >= p.n) return "sredina_slovo${k + 1}"
        }
        return null
    }

    public suspend fun destroyModel() {
        if(BuildConfig.DEBUG) Log.d(TAG, "destroyModel called")
        languageModel?.closeInternalLocked()
        languageModel = null
    }

    public fun close() {
        userDictionary.unregister()
    }

    private var trainingEnabled = false

    public fun launchProcessor() = lifecycleScope.launch {
        if(BuildConfig.DEBUG) Log.d(TAG, "Starting processor")
        launch {
            withContext(Dispatchers.Default) {
                TrainingWorkerStatus.lmRequest.collect {
                    if (it == LanguageModelFacilitatorRequest.ResetModel) {
                        if(BuildConfig.DEBUG) Log.d(TAG, "ResetModel event received, destroying model")
                        destroyModel()
                    }else if(it == LanguageModelFacilitatorRequest.ClearTrainingLog) {
                        historyLog.clear()
                        saveHistoryLog()
                    }
                }
            }
        }

        launch {
            withContext(Dispatchers.Default) {
                ModelPaths.modelOptionsUpdated.collect {
                    if(BuildConfig.DEBUG) Log.d(TAG, "ModelPaths options updated, destroying model")
                    skipLanguage = null
                    destroyModel()
                }
            }
        }
    }

    public fun shouldPassThroughToLegacy(): Boolean =
        shouldSkipForSwipe() || System.currentTimeMillis() < timeoutPauseUntil

    // alex: swipe rerangiranje ima svoj rok (400 ms), pa ga kasnjenja kod kucanja ne gase
    private fun shouldSkipForSwipe(): Boolean = when {
        (!settings.current.mTransformerPredictionEnabled) -> true
        (dictionaryFacilitator.primaryLocale.language == skipLanguage) -> true
        (transformerDisabled) -> true
        else -> false
    }

    // This method should return null if transformer is disabled by settings or locale
    var prevAcceptedLayout: Keyboard? = null
    var prevAcceptedLayoutResult = false
    fun makePredictionInputValues(inputStyle: Int): PredictionInputValues? {
        if(shouldPassThroughToLegacy()) return null

        if(inputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH
            || inputStyle == SuggestedWords.INPUT_STYLE_UPDATE_BATCH) return null

        val settingsValues = settings.current
        if (!settingsValues.needsToLookupSuggestions()) {
            return null
        }

        if(settingsValues.mInputAttributes.mIsEmailField) return null

        if(!inputLogic.mConnection.isConnected) return null

        try {
            val wordComposer = inputLogic.mWordComposer
            val ngramContext = inputLogic.getNgramContextFromNthPreviousWordForSuggestion(
                settingsValues.mSpacingAndPunctuations,
                2
            )

            if(wordComposer.isComposingWord
                && !context.getSetting(AllowTransformerOnNonQWERTYLayouts)
            ) {
                // Check we are on a supported layout
                if(prevAcceptedLayout != keyboardSwitcher.keyboard) {
                    if(keyboardSwitcher.keyboard?.mId?.mKeyboardLayoutSetName == "qwerty") {
                        prevAcceptedLayoutResult = true
                    } else {
                        val letters = keyboardSwitcher.keyboard?.sortedKeys?.filter {
                            settingsValues.isWordCodePoint(it.code) && it.code != '\''.code
                        }?.sortedBy {
                            it.x + 10000 * it.y
                        }?.joinToString(separator = "") { it.label.lowercase() } ?: ""

                        prevAcceptedLayoutResult = letters == "qwertyuiopasdfghjklzxcvbnm"
                    }
                }

                if(prevAcceptedLayoutResult == false)
                    return null
            }

            val values = PredictionInputValues(
                wordComposer.composedDataSnapshot,
                ngramContext,
                inputStyle,
                ++currentSequenceId
            )

            return values
        } catch(e: Exception) {
            if(e is CancellationException) throw e

            if(BuildConfig.DEBUG) Log.d(TAG, "Failed to get context, composed data snapshot, etc: $e")
            e.printStackTrace()
        }

        return null
    }

    private val historyLog: MutableList<HistoryLogForTraining> = mutableListOf()

    public fun addToHistory(
        word: String,
        wasAutoCapitalized: Boolean,
        ngramContext: NgramContext,
        timeStampInSeconds: Long,
        blockPotentiallyOffensive: Boolean,
        importance: Int
    ) {
        if(shouldPassThroughToLegacy()) return
        if(!trainingEnabled) return
        if(settings.current?.mInputAttributes?.mNoLearning != false) return

        if(dictionaryFacilitator.mostConfidentLocale != languageModel?.locale) return

        val wordCtx = ngramContext.fullContext.trim().lines().last()
        var committedNgramCtx = ngramContext.extractPrevWordsContext().replace(NgramContext.BEGINNING_OF_SENTENCE_TAG, " ").trim();
        if(committedNgramCtx.isEmpty()) {
            committedNgramCtx = " "
        }
        
        val lastIdx = wordCtx.lastIndexOf(committedNgramCtx)
        if(lastIdx == -1) {
            //println("addToHistory: extraction failed, couldn't find ngram ctx in full ctx")
            return
        }

        val misspelledWord = wordCtx.substring(
            lastIdx + committedNgramCtx.length
        )
        if(misspelledWord.isNotBlank() && (!(misspelledWord.startsWith(" ") || committedNgramCtx == " ") || misspelledWord.endsWith(" ") || misspelledWord.trim().contains(" "))) {
            //println("addToHistory: extraction failed bad context. wordCtx=[$wordCtx]  --   committedNgramCtx=[$committedNgramCtx]  --  word=[$word]  --  fullNgram=[$ngramContext]")
            return
        }

        val ctxBeforeMisspelledWord = wordCtx.dropLast(misspelledWord.length)

        val key = committedNgramCtx.trim() + " " + word.trim()
        val logToAdd = if(misspelledWord.isNotBlank()) {
            // Correcting (ctx) misspelled -> word
            HistoryLogForTraining(
                key,
                ctxBeforeMisspelledWord,
                committedNgramCtx,
                misspelledWord.trim(),
                word,
                importance,
                dictionaryFacilitator.primaryLocale.language,
                timeStampInSeconds
            )
        } else {
            // Predicted (ctx) -> word
            HistoryLogForTraining(
                key,
                ctxBeforeMisspelledWord,
                committedNgramCtx,
                null,
                word,
                importance,
                dictionaryFacilitator.primaryLocale.language,
                timeStampInSeconds
            )
        }

        historyLog.add(logToAdd)
        //println("addToHistory: Adding $logToAdd")
    }

    public fun unlearnFromHistory(
        word: String,
        ngramContext: NgramContext,
        timeStampInSeconds: Long,
        eventType: Int
    ) {
        if(shouldPassThroughToLegacy()) return
        if(!trainingEnabled) return

        val wordCtx = ngramContext.fullContext.trim().lines().last()
        var committedNgramCtx = ngramContext.extractPrevWordsContext().replace(NgramContext.BEGINNING_OF_SENTENCE_TAG, " ").trim();
        if(committedNgramCtx.isEmpty()) {
            committedNgramCtx = " "
        }
        
        val keyToSearch = committedNgramCtx.trim() + " " + word.trim()

        val logToRemove = historyLog.indexOfLast {
            it.key.startsWith(keyToSearch) || it.key == keyToSearch
        }

        if(logToRemove == -1) {
            //println("addToHistory: UNLEARN Couldn't find key $keyToSearch")
        } else {
            //println("addToHistory: Unlearning ${historyLog[logToRemove]}")
            historyLog.removeAt(logToRemove)
        }
    }

    public fun saveHistoryLog() {
        if(!context.getSetting(USE_TRANSFORMER_FINETUNING)) historyLog.clear()
        saveHistoryLogBackup(context, historyLog)
    }

    public fun loadHistoryLog() {
        assert(historyLog.isEmpty())
        loadHistoryLogBackup(context, historyLog)
    }

    public fun onStartInput() {
        transformerDisabled = false
        numConsecutiveTimeouts = 0
        timeoutPauseUntil = 0L
    }

    public fun isTransformerDisabled(): Boolean = transformerDisabled
}