package org.futo.inputmethod.engine.general

import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CancellationException
import org.futo.inputmethod.latin.uix.alex.NEXT_WORD_ROW_MAX
import org.futo.inputmethod.latin.uix.alex.NextWordRowState
import org.futo.inputmethod.latin.uix.alex.ShowNextWordRowSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.futo.inputmethod.annotations.UsedForTesting
import org.futo.inputmethod.engine.ExpandableSuggestionBarConfiguration
import org.futo.inputmethod.engine.GlobalIMEMessage
import org.futo.inputmethod.engine.IMEHelper
import org.futo.inputmethod.engine.IMEInterface
import org.futo.inputmethod.engine.IMEMessage
import org.futo.inputmethod.engine.NonExpandableSuggestionBar
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.event.InputTransaction
import org.futo.inputmethod.keyboard.KeyboardSwitcher
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.Dictionary
import org.futo.inputmethod.latin.DictionaryFacilitator
import org.futo.inputmethod.latin.DictionaryFacilitatorProvider
import org.futo.inputmethod.latin.NgramContext
import org.futo.inputmethod.latin.RichInputMethodManager
import org.futo.inputmethod.latin.Subtypes.switchToNextLanguage
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.SuggestionBlacklist
import org.futo.inputmethod.latin.WordComposer
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.latin.inputlogic.InputLogic
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.suggestions.SuggestionStripViewAccessor
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.actions.throwIfDebug
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.isDirectBootUnlocked
import org.futo.inputmethod.latin.utils.AsyncResultHolder
import org.futo.inputmethod.latin.xlm.LanguageModelFacilitator
import org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2
import java.util.concurrent.atomic.AtomicInteger

interface WordLearner {
    fun addToHistory(
        word: String,
        wasCapitalized: Boolean,
        ngramContext: NgramContext,
        timestamp: Long,
        blockOffensive: Boolean,
        importance: Int
    )

    fun removeFromHistory(
        word: String,
        ngramContext: NgramContext,
        timestamp: Long,
        eventType: Int
    )
}

interface OnGetSuggestedWordsCallbackWithInputStyle {
    fun onGetSuggestedWords(suggestedWords: SuggestedWords, inputStyle: Int, sequenceNumber: Int)
}

val UseExpandableSuggestionsForGeneralIME = SettingsKey(
    booleanPreferencesKey("use_expandable_suggestions_for_generalime"),
    false
)

/**
 * General IME implementation that works for Latin-based languages and some
 * more: Cyrillic, Hangul (with combiner), etc.
 *
 * Uses BinaryDictionary, etc
 */
class GeneralIME(val helper: IMEHelper) : IMEInterface, WordLearner, SuggestionStripViewAccessor, OnGetSuggestedWordsCallbackWithInputStyle {
    private val TAG = "GeneralIME"
    private val context = helper.context

    private val availabilityListener =
        object : DictionaryFacilitator.DictionaryInitializationListener {
            override fun onUpdateMainDictionaryAvailability(isMainDictionaryAvailable: Boolean) {
                helper.updateGestureAvailability(isGestureHandlingAvailable())
                updateSuggestions(SuggestedWords.INPUT_STYLE_TYPING)

                dictionaryFacilitator.updateSwipeLayoutAndDictsIfNeeded(
                    Settings.getInstance().current,
                    helper.keyboardSwitcher.keyboard
                )
            }
        }

    private val dictionaryFacilitator: DictionaryFacilitator =
        DictionaryFacilitatorProvider.getDictionaryFacilitator(
            false /* isNeededForSpellChecking */
        )

    private val inputLogic: InputLogic = InputLogic(
        helper, this, dictionaryFacilitator,
        this
    )

    private val settings = Settings.getInstance()

    private val suggestionBlacklist = SuggestionBlacklist(
        settings,
        context,
        helper.lifecycleScope
    )

    private val languageModelFacilitator = LanguageModelFacilitator(
        context = context,
        inputLogic = inputLogic,
        dictionaryFacilitator = dictionaryFacilitator,
        settings = settings,
        keyboardSwitcher = KeyboardSwitcher.getInstance(),
        lifecycleScope = helper.lifecycleScope,
        suggestionBlacklist = suggestionBlacklist,
        suggestedWordsCallback = this
    )

    override fun addToHistory(
        word: String,
        wasCapitalized: Boolean,
        ngramContext: NgramContext,
        timestamp: Long,
        blockOffensive: Boolean,
        importance: Int
    ) {
        val isWordBlacklisted = SuggestionBlacklist.getCapitalVariants(word, settings.current.mLocale).any { !suggestionBlacklist.isWordOk(it) }
        if(isWordBlacklisted) return

        dictionaryFacilitator.addToUserHistory(
            word, wasCapitalized,
            ngramContext, timestamp,
            blockOffensive
        )

        if (settings.current.mTransformerPredictionEnabled) {
            languageModelFacilitator.addToHistory(
                word, wasCapitalized,
                ngramContext, timestamp,
                blockOffensive, importance
            )
        }
    }

    override fun removeFromHistory(
        word: String,
        ngramContext: NgramContext,
        timestamp: Long,
        eventType: Int
    ) {
        dictionaryFacilitator.unlearnFromUserHistory(
            word, ngramContext, timestamp, eventType
        )

        if (settings.current.mTransformerPredictionEnabled) {
            languageModelFacilitator.unlearnFromHistory(
                word, ngramContext, timestamp, eventType
            )
        }
    }


    fun resetDictionaryFacilitator(force: Boolean = false, reloadAllDicts: Boolean = false) {
        if(!context.isDirectBootUnlocked) return

        val settings = settings.current

        val locales = settings.mInputAttributes.mLocaleOverride?.let {
            listOf(it)
        } ?: RichInputMethodManager.getInstance().currentSubtypeLocales
        if ((!force)
            && dictionaryFacilitator.isForLocales(locales)
            && dictionaryFacilitator.isForAccount(settings.mAccount)
        ) {
            return
        }

        dictionaryFacilitator.resetDictionaries(
            context,
            locales, settings.mUseContactsDict,
            settings.mUsePersonalizedDicts,
            reloadAllDicts,
            settings.mAccount, "",  /* dictNamePrefix */
            availabilityListener /* DictionaryInitializationListener */
        )

        if (settings.mAutoCorrectionEnabledPerUserSettings) {
            inputLogic.mSuggest.setAutoCorrectionThreshold(
                settings.mAutoCorrectionThreshold
            )
        }
        inputLogic.mSuggest.setPlausibilityThreshold(
            settings.mPlausibilityThreshold
        )
    }

    override fun onCreate() {
        languageModelFacilitator.launchProcessor()
        org.futo.inputmethod.latin.uix.alex.AiUsage.init(context) // alex: brojanje AI troska

        // alex: red za sledecu rec trazi predloge preko transformera
        NextWordRowState.requester = { before ->
            helper.lifecycleScope.launch(languageModelFacilitator.languageModelScope) {
                val words = try { languageModelFacilitator.predictNextWords(before, null) } catch(e: Exception) {
                    if(e is CancellationException) throw e
                    emptyList()
                }
                NextWordRowState.words.value = words
            }
        }
        if (context.isDirectBootUnlocked) onDeviceUnlocked()

        suggestionBlacklist.init()

        helper.lifecycleScope.launch {
            GlobalIMEMessage.collect { message ->
                when(message) {
                    IMEMessage.ReloadResources -> withContext(Dispatchers.Main) {
                        resetDictionaryFacilitator(force = true, reloadAllDicts = true)
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onDestroy() {
        dictionaryFacilitator.closeDictionaries()
        languageModelFacilitator.saveHistoryLog()

        runBlocking {
            languageModelFacilitator.destroyModel()
            languageModelFacilitator.close()
        }
    }

    override fun onDeviceUnlocked() {
        languageModelFacilitator.loadHistoryLog()
    }

    private val expandableExpandableCfg = ExpandableSuggestionBarConfiguration(true, false)
    private var expandableCfg: ExpandableSuggestionBarConfiguration = NonExpandableSuggestionBar
    override fun onStartInput() {
        expandableCfg = if(helper.context.getSetting(UseExpandableSuggestionsForGeneralIME)) {
            expandableExpandableCfg
        } else {
            NonExpandableSuggestionBar
        }

        resetDictionaryFacilitator()
        setNeutralSuggestionStrip()
        dictionaryFacilitator.onStartInput()
        languageModelFacilitator.onStartInput()
        // alex: automatska AI provera pocinje iz pocetka za svako polje
        org.futo.inputmethod.latin.uix.alex.AutoAiCheck.reset()
        org.futo.inputmethod.latin.uix.alex.AutoAiCheck.fixAllHandler = { fixAllAutoAi() }
        org.futo.inputmethod.latin.uix.alex.AutoAiCheck.runHandler = { runAutoAiCheck() }
        inputLogic.startInput(
            RichInputMethodManager.getInstance().combiningRulesExtraValueOfCurrentSubtype,
            settings.current
        )

        cancelSync()
    }

    var dictSyncJob: Job? = null
    private fun delayedSync() {
        if (!context.isDirectBootUnlocked) return

        dictSyncJob?.cancel()
        dictSyncJob = helper.lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                delay(5000L)
                withContext(Dispatchers.Main) {
                    dictionaryFacilitator.flushUserHistoryDictionaries()
                    languageModelFacilitator.saveHistoryLog()
                }
            }
        }
    }

    private fun cancelSync() {
        dictSyncJob?.cancel()
    }

    override fun onOrientationChanged() {
        inputLogic.onOrientationChange(settings.current)
    }

    override fun onFinishInput() {
        org.futo.inputmethod.latin.uix.alex.AutoAiCheck.reset()
        inputLogic.finishInput()
        dictionaryFacilitator.onFinishInput(context)
        updateSuggestionJob?.cancel()
        delayedSync()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        composingSpanStart: Int,
        composingSpanEnd: Int
    ) {
        inputLogic.onUpdateSelection(
            oldSelStart, oldSelEnd,
            newSelStart, newSelEnd,
            composingSpanStart, composingSpanEnd,
            Settings.getInstance().current
        )
        lastSelStart = newSelStart; lastSelEnd = newSelEnd
        lastCompStart = composingSpanStart; lastCompEnd = composingSpanEnd
    }

    // alex: poslednji poznat kursor i rec koja se kuca (za crvene linije AI provere)
    private var lastSelStart = -1
    private var lastSelEnd = -1
    private var lastCompStart = -1
    private var lastCompEnd = -1

    // Oznaci pogresne reci u polju kao "pogresno napisane" (SuggestionSpan), pa ih aplikacija
    // podvuce crveno, kao Androidova provera pravopisa. Radi samo gde aplikacija podrzava te oznake.
    private fun applyAutoAiUnderlines(text: String) {
        val A = org.futo.inputmethod.latin.uix.alex.AutoAiCheck
        val ic = helper.getCurrentInputConnection() ?: return
        if(lastSelStart < 0) return
        val before = ic.getTextBeforeCursor(4000, 0)?.toString() ?: return
        val base = lastSelStart - before.length
        if(base < 0) return
        val selS = lastSelStart; val selE = lastSelEnd
        val compS = lastCompStart; val compE = lastCompEnd
        val newOnes = A.issues.map { it.original }
        try {
            ic.beginBatchEdit()
            for(w in A.underlined) if(w !in newOnes) markAutoAiWord(ic, text, base, w, null, compS, compE)
            for(iss in A.issues) markAutoAiWord(ic, text, base, iss.original, iss.replacement, compS, compE)
            if(compS in 0 until compE) ic.setComposingRegion(compS, compE)
            ic.setSelection(selS, selE)
        } catch(e: Exception) {
            Log.e(TAG, "AI underline failed", e)
        } finally {
            ic.endBatchEdit()
        }
        A.underlined = newOnes
    }

    private fun markAutoAiWord(
        ic: android.view.inputmethod.InputConnection, text: String, base: Int,
        word: String, fix: String?, compS: Int, compE: Int
    ) {
        var from = 0
        while(true) {
            val at = text.indexOf(word, from)
            if(at < 0) break
            from = at + word.length
            if(at > 0 && text[at - 1].isLetterOrDigit()) continue          // samo cela rec
            if(from < text.length && text[from].isLetterOrDigit()) continue
            val s = base + at
            val e = s + word.length
            if(compS >= 0 && s < compE && e > compS) continue                // rec koja se upravo kuca
            val sp = android.text.SpannableString(word)
            if(fix != null) {
                sp.setSpan(
                    android.text.style.SuggestionSpan(context, arrayOf(fix),
                        android.text.style.SuggestionSpan.FLAG_EASY_CORRECT or android.text.style.SuggestionSpan.FLAG_MISSPELLED),
                    0, word.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            ic.setComposingRegion(s, e)
            ic.setComposingText(sp, 1)
            ic.finishComposingText()
        }
    }

    // alex: automatska AI provera na pauzu (vidi uix/alex/AutoAiCheck.kt)
    private fun readFieldTextForAi(): String? {
        val ic = helper.getCurrentInputConnection() ?: return null
        val before = ic.getTextBeforeCursor(4000, 0)?.toString() ?: return null
        val after = ic.getTextAfterCursor(4000, 0)?.toString() ?: ""
        return before + after
    }

    // alex: brza AI provera (ikonica "AI brza provera", npr. kao Action key pored ?123). Vraca true ako je pokrenuta.
    private fun runAutoAiCheck(): Boolean {
        val A = org.futo.inputmethod.latin.uix.alex.AutoAiCheck
        val attrs = settings.current.mInputAttributes
        if(attrs.mIsPasswordField || attrs.mNoLearning || !settings.current.isPersonalizationEnabled) return false
        helper.keyboardSwitcher.mainKeyboardView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        A.job?.cancel()
        A.job = helper.lifecycleScope.launch {
            val text = readFieldTextForAi() ?: return@launch
            if(text == A.lastCheckedText) {
                // isti tekst: samo ponovo pokazi poslednji rezultat, bez novog poziva
                showAutoAiResult()
                return@launch
            }
            val wordCount = text.split(' ', '\n', '\t').count { it.isNotBlank() }
            if(wordCount < org.futo.inputmethod.latin.uix.alex.AUTO_AI_MIN_WORDS) {
                A.issues = emptyList()
                A.status.value = org.futo.inputmethod.latin.uix.alex.AutoAiStatus.Idle
                return@launch
            }
            val key = context.getSetting(org.futo.inputmethod.latin.uix.actions.aicheck.AiCheckApiKey)
            if(key.isBlank()) return@launch
            val model = context.getSetting(org.futo.inputmethod.latin.uix.actions.aicheck.AiCheckModel)
            A.hideJob?.cancel()
            A.status.value = org.futo.inputmethod.latin.uix.alex.AutoAiStatus.Checking
            val result = try {
                withContext(Dispatchers.IO) {
                    org.futo.inputmethod.latin.uix.actions.aicheck.AiCheckClient.check(key, model, text)
                }
            } catch(e: CancellationException) {
                throw e
            } catch(e: Exception) {
                Log.e(TAG, "auto AI check failed", e)
                A.status.value = org.futo.inputmethod.latin.uix.alex.AutoAiStatus.Failed
                return@launch
            }
            // alex: tekst je mogao malo da se promeni dok je provera trajala (npr. razmak posle drzanja);
            // greske se traze u tekstu kakav je SADA, a ne odbacuju se
            val now = readFieldTextForAi() ?: text
            A.lastCheckedText = now
            A.issues = result.issues.mapNotNull {
                val o = it.original ?: return@mapNotNull null
                val r = it.replacement ?: return@mapNotNull null
                if(o.isBlank() || o == r || !now.contains(o)) null
                else org.futo.inputmethod.latin.uix.alex.AutoIssue(o, r)
            }
            showAutoAiResult()
            applyAutoAiUnderlines(now)
        }
        return true
    }

    private fun showAutoAiResult() {
        val A = org.futo.inputmethod.latin.uix.alex.AutoAiCheck
        A.hideJob?.cancel()
        if(A.issues.isEmpty()) {
            A.status.value = org.futo.inputmethod.latin.uix.alex.AutoAiStatus.Ok
            A.hideJob = helper.lifecycleScope.launch {
                delay(2000L)
                if(A.status.value == org.futo.inputmethod.latin.uix.alex.AutoAiStatus.Ok)
                    A.status.value = org.futo.inputmethod.latin.uix.alex.AutoAiStatus.Idle
            }
        } else {
            A.status.value = org.futo.inputmethod.latin.uix.alex.AutoAiStatus.Errors(A.issues.size)
        }
    }

    // tap na oznaku "N greske": ispravi sve u polju
    private fun fixAllAutoAi() {
        val A = org.futo.inputmethod.latin.uix.alex.AutoAiCheck
        val ic = helper.getCurrentInputConnection() ?: return
        val text = readFieldTextForAi() ?: return
        var newText = text
        for(iss in A.issues) {
            val at = newText.indexOf(iss.original)
            if(at >= 0) newText = newText.substring(0, at) + iss.replacement + newText.substring(at + iss.original.length)
        }
        if(newText != text) {
            ic.beginBatchEdit()
            try {
                ic.finishComposingText()
                val b = ic.getTextBeforeCursor(4000, 0)?.length ?: 0
                val a = ic.getTextAfterCursor(4000, 0)?.length ?: 0
                ic.deleteSurroundingText(b, a)
                ic.commitText(newText, 1)
            } finally {
                ic.endBatchEdit()
            }
        }
        A.issues = emptyList()
        A.underlined = emptyList()
        A.lastCheckedText = newText
        showAutoAiResult()
    }

    override fun isGestureHandlingAvailable(): Boolean =
        dictionaryFacilitator.hasAtLeastOneInitializedMainDictionary()

    private fun onEventInternal(event: Event, ignoreSuggestionUpdate: Boolean = false) {
        helper.requestCursorUpdate()

        val cursorBefore = inputLogic.mConnection.mExpectedSelStart

        val inputTransaction = when (event.eventType) {
            Event.EVENT_TYPE_INPUT_KEYPRESS,
            Event.EVENT_TYPE_INPUT_KEYPRESS_RESUMED -> {
                inputLogic.onCodeInput(
                    settings.current,
                    event,
                    helper.keyboardShiftMode,
                    helper.currentKeyboardScriptId
                )
            }

            Event.EVENT_TYPE_SOFTWARE_GENERATED_STRING -> {
                inputLogic.onTextInput(
                    settings.current,
                    event,
                    helper.keyboardShiftMode
                )
            }

            Event.EVENT_TYPE_SUGGESTION_PICKED -> {
                inputLogic.onPickSuggestionManually(
                    settings.current,
                    event.mSuggestedWordInfo!!,
                    helper.keyboardShiftMode,
                    helper.currentKeyboardScriptId
                )
            }

            Event.EVENT_TYPE_DOWN_UP_KEYEVENT -> {
                inputLogic.sendDownUpKeyEvent(
                    event.mKeyCode,
                    event.mX
                )
                InputTransaction(
                    settings.current,
                    event,
                    System.currentTimeMillis(),
                    0,
                    0
                ).apply { setRequiresUpdateSuggestions() }
            }

            Event.EVENT_TYPE_NOT_HANDLED -> { null }

            Event.EVENT_TYPE_TOGGLE -> { null }

            Event.EVENT_TYPE_MODE_KEY -> { null }

            Event.EVENT_TYPE_GESTURE -> { null }

            Event.EVENT_TYPE_CURSOR_MOVE -> { null }
            else -> { null }
        }

        inputLogic.mConnection.send()

        val cursorAfter = inputLogic.mConnection.mExpectedSelStart

        inputLogic.afterEventCursorDelta(cursorBefore, cursorAfter - cursorBefore)

        when(inputTransaction?.requiredShiftUpdate) {
            InputTransaction.SHIFT_UPDATE_LATER,
            InputTransaction.SHIFT_UPDATE_NOW ->
                helper.keyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState())
        }

        if(inputTransaction?.requiresUpdateSuggestions() == true && !ignoreSuggestionUpdate) {
            val inputStyle = if(inputTransaction.mEvent.isSuggestionStripPress) {
                SuggestedWords.INPUT_STYLE_NONE
            } else if(inputTransaction.mEvent.isGesture) {
                SuggestedWords.INPUT_STYLE_TAIL_BATCH
            } else {
                SuggestedWords.INPUT_STYLE_TYPING
            }

            updateSuggestions(inputStyle)
        }
    }

    override fun onEvent(event: Event) = onEventInternal(event)

    override fun onGetSuggestedWords(
        suggestedWords: SuggestedWords,
        inputStyle: Int,
        sequenceNumber: Int
    ) = onGetSuggestedWordsInternal(suggestedWords, inputStyle, sequenceNumber, true)

    private fun onGetSuggestedWordsInternal(
        suggestedWords: SuggestedWords,
        inputStyle: Int,
        sequenceNumber: Int,
        swipeDistinct: Boolean = true
    ) {
        if(sequenceNumber < sequenceId.get() && inputStyle != SuggestedWords.INPUT_STYLE_TAIL_BATCH) {
            return
        }
        val unfilteredWords = when {
            suggestedWords.isEmpty && (inputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH ||
                    inputStyle == SuggestedWords.INPUT_STYLE_UPDATE_BATCH
                    ) -> inputLogic.mSuggestedWords

            else -> suggestedWords
        }

        val words = unfilteredWords?.let { suggestionBlacklist.filterBlacklistedSuggestions(it) }
            ?: SuggestedWords.getEmptyInstance()


        // If any blacklisted learned words are here, we must unlearn them
        val wordsToUnlearn = unfilteredWords.mSuggestedWordInfoList
            .filter { it.mSourceDict?.mDictType == Dictionary.TYPE_USER_HISTORY }
            .filterNot { suggestionBlacklist.isSuggestedWordOk(it) }
        wordsToUnlearn.forEach {
            removeFromHistory(
                it.mWord,
                NgramContext.EMPTY_PREV_WORDS_INFO,
                -1,
                Constants.NOT_A_CODE
            )
        }

        showSuggestionStrip(words)
        when(inputStyle) {
            SuggestedWords.INPUT_STYLE_TAIL_BATCH -> {
                val updateBatch = {
                    inputLogic.onUpdateTailBatchInputCompleted(
                        settings.current,
                        words,
                        helper.keyboardSwitcher,
                        swipeDistinct
                    )
                    // alex: swipe log: ukupno od kraja poteza do reci u tekstu
                    alexPendingLog?.let { rec ->
                        alexPendingLog = null
                        alexFlushSwipeLog(rec, "\nukupno: od kraja poteza do reci u tekstu %d ms".format(
                            android.os.SystemClock.uptimeMillis() - alexSwipeEndAt))
                    }

                    if(settings.current.mVibrateOn) {
                        helper.keyboardSwitcher.mainKeyboardView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
                    }
                }
                if(Looper.myLooper() != Looper.getMainLooper()) {
                    helper.lifecycleScope.launch(Dispatchers.Main) { updateBatch() }
                } else {
                    updateBatch()
                }
            }
        }
    }

    var updateSuggestionJob: Job? = null
    var lmUpdateJob: Job? = null
    private suspend fun updateSuggestionsDictionaryInternal(inputStyle: Int, sequenceNumber: Int) {
        // This method returns null for us if LM is disabled
        val predictionInputValues = languageModelFacilitator.makePredictionInputValues(inputStyle)

        var dictResult: SuggestedWords? = null
        var lmResult: ArrayList<SuggestedWordInfo>? = null
        if(predictionInputValues != null) {
            // This runs asynchronously
            val lmResultHolder = AsyncResultHolder<ArrayList<SuggestedWordInfo>?>("LMSuggest")
            lmUpdateJob?.cancel()
            lmUpdateJob = helper.lifecycleScope.launch(languageModelFacilitator.languageModelScope) {
                val result = languageModelFacilitator.getLanguageModelSuggestions(predictionInputValues)
                lmResultHolder.set(result ?: arrayListOf())
            }

            // This runs synchronously
            inputLogic.getSuggestedWords(
                settings.current,
                helper.keyboardSwitcher.keyboard ?: return,
                helper.keyboardShiftMode,
                inputStyle,
                sequenceNumber
            ) { suggestedWords -> dictResult = suggestedWords }

            // Wait for LM to report result
            lmResult = lmResultHolder.get(null, 350L)
            if(lmResult == null) languageModelFacilitator.reportTimeout()
        } else {
            val alexDec0 = android.os.SystemClock.uptimeMillis()
            inputLogic.getSuggestedWords(
                settings.current,
                helper.keyboardSwitcher.keyboard ?: return,
                helper.keyboardShiftMode,
                inputStyle,
                sequenceNumber
            ) { suggestedWords -> dictResult = suggestedWords }
            alexDecoderMs = android.os.SystemClock.uptimeMillis() - alexDec0

            // alex: swipe kandidate ponovo rangira transformer (ako stigne na vreme)
            val swiped = dictResult
            if(inputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH && swiped != null) {
                // alex: swipe log (samo kad je ukljucen; nikad u poljima za lozinku/inkognito)
                val attrs = settings.current.mInputAttributes
                val logIt = context.getSetting(org.futo.inputmethod.latin.uix.alex.SwipeLogSetting) &&
                        !attrs.mIsPasswordField && !attrs.mNoLearning && settings.current.isPersonalizationEnabled
                val dbg = if(logIt) StringBuilder() else null
                languageModelFacilitator.swipeDebug = dbg
                val t0 = System.currentTimeMillis()
                var finished = false
                val rescored = try {
                    withTimeoutOrNull(400L) { languageModelFacilitator.rescoreSwipe(swiped).also { finished = true } }
                } catch(e: Exception) {
                    if(e is CancellationException) throw e
                    Log.e(TAG, "swipe rescore failed", e)
                    dbg?.append("greska: $e\n")
                    null
                } finally {
                    languageModelFacilitator.swipeDebug = null
                }
                if(rescored != null) dictResult = rescored
                // alex: naucene zamene iz korisnikovih izbora (Settings -> Swipe -> Uci iz mojih izbora)
                try {
                    val dr = dictResult
                    if(dr != null && dr.size() > 1) {
                        // nova swipe rec jos nije u tekstu: tekst pre kursora se zavrsava prethodnom reci
                        val prev = org.futo.inputmethod.latin.uix.alex.SwipePairs.prevFromText(
                            inputLogic.mConnection.getTextBeforeCursor(80, 0))
                        org.futo.inputmethod.latin.uix.alex.SwipePairs.apply(
                            context, prev, dr.mSuggestedWordInfoList.map { it.mWord }
                        )?.let { (i, line) ->
                            val l = ArrayList(dr.mSuggestedWordInfoList)
                            l.add(0, l.removeAt(i))
                            dictResult = SuggestedWords(l, dr.mRawSuggestions, l[0], true, false, false,
                                dr.mInputStyle, dr.mSequenceNumber)
                            dbg?.append(line)?.append('\n')
                        }
                    }
                } catch(e: Exception) {
                    if(e is CancellationException) throw e
                }
                if(dbg != null) {
                    val ms = System.currentTimeMillis() - t0
                    val head = "podesavanja: swipe=%.2f a=%.1f T=%.1f ctx=%d tema=%.1f | vreme %d ms%s".format(
                        context.getSetting(org.futo.inputmethod.latin.xlm.SwipeLmWeightSetting),
                        context.getSetting(org.futo.inputmethod.latin.xlm.BinaryDictTransformerWeightSetting),
                        context.getSetting(org.futo.inputmethod.latin.xlm.AutocorrectThresholdSetting),
                        org.futo.inputmethod.latin.xlm.lmContextChars(context),
                        org.futo.inputmethod.latin.uix.alex.AiTopic.strength(context),
                        ms, if(!finished) " (ISTEKAO ROK 400 ms)" else "")
                    val before = try { inputLogic.mConnection.getTextBeforeCursor(60, 0)?.toString() } catch(e: Exception) { null }
                    val shown = dictResult?.mSuggestedWordInfoList?.take(6)?.joinToString(" ") {
                        it.mWord + (if(it.mOriginatesFromTransformerLM) "[T]" else if(it.mOriginatesFromSwipeModel) "[S]" else "")
                    }
                    val sinceEnd = android.os.SystemClock.uptimeMillis() - alexSwipeEndAt
                    val timing = "\nvreme: dekoder %d ms, transformer %d ms, ostalo %d ms (od toga cekanje u redu %d ms)".format(
                        alexDecoderMs, ms, sinceEnd - alexDecoderMs - ms, alexWaitMs)
                    alexPendingLog?.let { alexFlushSwipeLog(it, "\nukupno: nije prikazano") }
                    alexPendingLog = (
                        head + timing + "\njezik: " + languageModelFacilitator.dictionaryFacilitator.mostConfidentLocale +
                        "\nrecnik: " + org.futo.inputmethod.latin.uix.alex.SwipeLog.dictionaryLabel(
                            context, languageModelFacilitator.dictionaryFacilitator.primaryLocale) +
                        "\ntransformer: " + languageModelFacilitator.modelLabel() +
                        "\npre kursora: [" + (before ?: "") + "]\n" + dbg.toString() + "prikazano: " + shown)
                }

                // alex: red za sledecu rec (ako je ukljucen)
                val topWord = dictResult?.mSuggestedWordInfoList?.firstOrNull()?.mWord
                if(topWord != null && context.getSetting(ShowNextWordRowSetting)) {
                    helper.lifecycleScope.launch(languageModelFacilitator.languageModelScope) {
                        val words = try { languageModelFacilitator.predictNextWords(null, topWord) } catch(e: Exception) {
                            if(e is CancellationException) throw e
                            emptyList()
                        }
                        NextWordRowState.words.value = words
                    }
                }
            }
        }

        when {
            !lmResult.isNullOrEmpty() && dictResult != null && predictionInputValues != null -> {
                val processed = languageModelFacilitator.processAndMergeSuggestions(
                    predictionInputValues,
                    dictResult,
                    lmResult
                )
                if(processed != null) {
                    onGetSuggestedWords(processed, inputStyle, sequenceNumber)
                } else {
                    throwIfDebug(IllegalStateException(
                        "The processAndMergeSuggestions method should not typically return null"
                    ))

                    onGetSuggestedWords(dictResult, inputStyle, sequenceNumber)
                }
            }

            dictResult != null -> {
                onGetSuggestedWords(dictResult, inputStyle, sequenceNumber)
            }

            // Note: we don't support LM results but not dict
            else -> {
                setNeutralSuggestionStrip()
            }
        }
    }


    private var sequenceId = AtomicInteger(0)
    private val sequenceIdCompleted = AtomicInteger(0)
    private val computationMutex = Mutex()
    private var timeTakenToUpdate = 40L

    // alex: merenje vremena swipe-a za log (sve u uptimeMillis)
    @Volatile private var alexSwipeEndAt = 0L
    @Volatile private var alexWaitMs = -1L
    @Volatile private var alexDecoderMs = -1L
    @Volatile private var alexPendingLog: String? = null

    private fun alexFlushSwipeLog(rec: String, tail: String) {
        helper.lifecycleScope.launch(Dispatchers.IO) {
            org.futo.inputmethod.latin.uix.alex.SwipeLog.append(context, rec + tail)
        }
    }
    fun updateSuggestions(inputStyle: Int) {
        updateSuggestionJob?.cancel()
        if(inputStyle == SuggestedWords.INPUT_STYLE_TYPING) NextWordRowState.clear() // alex

        val isSwipe = inputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH
                || inputStyle == SuggestedWords.INPUT_STYLE_UPDATE_BATCH

        if(!isSwipe) {
            if (!settings.current.needsToLookupSuggestions()) {
                setNeutralSuggestionStrip()
                return
            }

            if (!inputLogic.mWordComposer.isComposingWord && !settings.current.mBigramPredictionEnabled) {
                setNeutralSuggestionStrip()
                return
            }
        }

        val seqId = sequenceId.incrementAndGet()

        val delayTime = when {
            // With transformer off keep 40ms static delay for legacy reasons (less battery use)
            languageModelFacilitator.shouldPassThroughToLegacy() -> 40L

            // On fast devices, prefer to wait less to improve responsiveness
            else -> 40L.coerceAtMost(timeTakenToUpdate / 2).coerceAtLeast(16L)
        }

        val alexQueuedAt = android.os.SystemClock.uptimeMillis()
        updateSuggestionJob = helper.lifecycleScope.launch {
            when(inputStyle) {
                SuggestedWords.INPUT_STYLE_TYPING -> delay(delayTime)
            }
            withContext(NonCancellable + dictionaryScope) {
                computationMutex.withLock {
                    // double check in case sequence id incremented after we acquired scope
                    if (sequenceId.get() > seqId) return@withContext
                    if(inputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH)
                        alexWaitMs = android.os.SystemClock.uptimeMillis() - alexQueuedAt

                    val t0 = System.currentTimeMillis()
                    updateSuggestionsDictionaryInternal(inputStyle, seqId)
                    val t1 = System.currentTimeMillis()
                    timeTakenToUpdate = (timeTakenToUpdate + (t1 - t0)) / 2

                    if(BuildConfig.DEBUG) Log.d(TAG, "Time taken for suggestions update = ${t1-t0} ms (avg $timeTakenToUpdate ms)")

                    sequenceIdCompleted.set(seqId)
                }
            }
        }
    }

    fun ensureSuggestionsCompleted(): Boolean {
        val currJob = updateSuggestionJob

        val seqId = sequenceId.get()
        if(sequenceIdCompleted.get() < seqId) {
            currJob?.cancel()

            val newJob = helper.lifecycleScope.launch(NonCancellable + dictionaryScope) {
                if(sequenceIdCompleted.get() < seqId) {
                    updateSuggestionsDictionaryInternal(SuggestedWords.INPUT_STYLE_TYPING, seqId)
                    sequenceIdCompleted.set(seqId)
                }
            }

            updateSuggestionJob = newJob

            return runBlocking {
                (withTimeoutOrNull(450L) {
                    newJob.join()
                    true
                } == true)
            }
        }
        return true
    }

    override fun onStartBatchInput() {
        inputLogic.onStartBatchInput(
            settings.current,
            helper.keyboardSwitcher,
        )
    }

    override fun onUpdateBatchInput(batchPointers: InputPointers?) {
        inputLogic.onUpdateBatchInput(batchPointers)
    }

    override fun onEndBatchInput(batchPointers: InputPointers?) {
        alexSwipeEndAt = android.os.SystemClock.uptimeMillis() // alex: merenje za swipe log
        inputLogic.onEndBatchInput(batchPointers)
    }

    override fun onCancelBatchInput() {
        inputLogic.onCancelBatchInput()
    }

    override fun onCancelInput() {
        // GeneralIME does nothing
    }

    override fun onFinishSlidingInput() {
        // GeneralIME does nothing
    }

    override fun onCustomRequest(requestCode: Int): Boolean {
        if(requestCode == Constants.CUSTOM_CODE_ALEX_AI_CHECK) return runAutoAiCheck()
        return false
    }

    override fun onMovePointer(steps: Int, stepOverWords: Boolean, select: Boolean?) {
        setNeutralSuggestionStrip()

        val shiftMode: Int = helper.keyboardShiftMode
        val select = select
            ?: ((shiftMode == WordComposer.CAPS_MODE_MANUAL_SHIFTED) || (shiftMode == WordComposer.CAPS_MODE_MANUAL_SHIFT_LOCKED))

        if (select) {
            inputLogic.disableRecapitalization()
        }

        if (steps < 0) {
            inputLogic.cursorLeft(steps, stepOverWords, select)
        } else {
            inputLogic.cursorRight(steps, stepOverWords, select)
        }
    }

    override fun onMoveDeletePointer(steps: Int) {
        setNeutralSuggestionStrip()
        if (inputLogic.mConnection.hasCursorPosition()) {
            val stepOverWords =
                settings.current.mBackspaceMode == Settings.BACKSPACE_MODE_WORDS
            if (steps < 0) {
                inputLogic.cursorLeft(steps, stepOverWords, true)
            } else {
                inputLogic.cursorRight(steps, stepOverWords, true)
            }
        } else {
            var steps = steps
            while (steps < 0) {
                onEvent(
                    Event.createSoftwareKeypressEvent(
                        Event.NOT_A_CODE_POINT,
                        Constants.CODE_DELETE,
                        Constants.NOT_A_COORDINATE,
                        Constants.NOT_A_COORDINATE,
                        false
                    )
                )
                steps++
            }
        }
    }

    override fun onUpWithDeletePointerActive() {
        if (inputLogic.mConnection.hasSelection()) {
            val selection: CharSequence? = inputLogic.mConnection.getSelectedText(0)

            onEventInternal(
                Event.createSoftwareKeypressEvent(
                    Event.NOT_A_CODE_POINT,
                    Constants.CODE_DELETE,
                    Constants.NOT_A_COORDINATE,
                    Constants.NOT_A_COORDINATE,
                    false
                ),
                ignoreSuggestionUpdate = true
            )

            if (selection != null && settings.current.mInputAttributes.mShouldShowSuggestions) {
                val info = ArrayList<SuggestedWordInfo?>()
                info.add(
                    SuggestedWordInfo(
                        selection.toString(),
                        "",
                        0,
                        SuggestedWordInfo.KIND_UNDO,
                        null,
                        0,
                        0
                    )
                )
                showSuggestionStrip(
                    SuggestedWords(
                        info,
                        null,
                        null,
                        false,
                        false,
                        false,
                        0,
                        0
                    )
                )
            }
        } else {
            onUpWithPointerActive()
        }
    }

    override fun onUpWithPointerActive() {
        inputLogic.restartSuggestionsOnWordTouchedByCursor(
            settings.current, null,
            false,
            helper.currentKeyboardScriptId
        )
    }

    override fun onSwipeLanguage(direction: Int) {
        switchToNextLanguage(context, direction)
    }

    override fun onMovingCursorLockEvent(canMoveCursor: Boolean) {
        // GeneralIME does nothing
    }

    override fun clearUserHistoryDictionaries() {
        dictionaryFacilitator.clearUserHistoryDictionary(context)
        resetDictionaryFacilitator(force = true)
    }

    override fun requestSuggestionRefresh() {
        inputLogic.mSuggestedWords?.let {
            onGetSuggestedWordsInternal(
                it,
                it.mInputStyle,
                sequenceIdCompleted.get(),
                false
            )
        }
    }

    override fun getCurrentAutoCapsState(): Int =
        inputLogic.getCurrentAutoCapsState(settings.current)

    override fun onLayoutUpdated(layout: KeyboardLayoutSetV2) {
        inputLogic.mWordComposer.setCombiners(layout.mainLayout.combiners)

        dictionaryFacilitator.updateSwipeLayoutAndDictsIfNeeded(
            Settings.getInstance().current,
            helper.keyboardSwitcher.keyboard
        )
    }

    override fun setNeutralSuggestionStrip() {
        inputLogic.setSuggestedWords(SuggestedWords.getEmptyInstance())
        helper.setNeutralSuggestionStrip(expandableCfg)
    }

    override fun showSuggestionStrip(words: SuggestedWords?) {
        // alex: kad je drugi red ukljucen, predvidjanja (posle razmaka) idu u drugi red,
        // a gornji ostaje samo za ispravke i dopune reci koja se kuca/swipe-uje
        if(words != null && words.isPrediction && context.getSetting(ShowNextWordRowSetting)) {
            NextWordRowState.topWords.value = emptySet()
            NextWordRowState.words.value = words.mSuggestedWordInfoList
                .map { it.mWord }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .take(NEXT_WORD_ROW_MAX)
            inputLogic.setSuggestedWords(SuggestedWords.getEmptyInstance())
            helper.setNeutralSuggestionStrip(expandableCfg)
            return
        }
        // alex: kursor u reci koju je AI oznacio -> ispravka je prvi predlog (tap menja samo tu rec)
        val aiFix = if(words != null && !words.isPrediction &&
            org.futo.inputmethod.latin.uix.alex.AutoAiCheck.issues.isNotEmpty()) {
            val typed = inputLogic.mWordComposer.typedWord
            if(typed.isNullOrEmpty()) null
            else org.futo.inputmethod.latin.uix.alex.AutoAiCheck.replacementFor(typed)?.takeIf { it != typed }
        } else null
        if(aiFix != null && words != null) {
            val info = SuggestedWordInfo(aiFix, "", Int.MAX_VALUE, SuggestedWordInfo.KIND_CORRECTION,
                Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE)
            val rest = words.mSuggestedWordInfoList.filter { it.mWord != aiFix }
            val list = ArrayList<SuggestedWordInfo>(listOf(info) + rest)
            val withFix = SuggestedWords(list, words.mRawSuggestions, words.mTypedWordInfo, words.mTypedWordValid,
                false, words.mIsObsoleteSuggestions, words.mInputStyle, words.mSequenceNumber)
            inputLogic.setSuggestedWords(withFix)
            NextWordRowState.topWords.value = list.map { it.mWord.lowercase() }.toSet()
            if(settings.current.isSuggestionsEnabledPerUserSettings) helper.showSuggestionStrip(withFix, expandableCfg)
            else helper.setNeutralSuggestionStrip(expandableCfg)
            return
        }
        inputLogic.setSuggestedWords(words)
        // alex: reci iz gornjeg reda, da ih donji red ne ponavlja
        NextWordRowState.topWords.value = words?.mSuggestedWordInfoList?.map { it.mWord.lowercase() }?.toSet() ?: emptySet()

        if(settings.current.isSuggestionsEnabledPerUserSettings) {
            helper.showSuggestionStrip(words, expandableCfg)
        } else {
            helper.setNeutralSuggestionStrip(expandableCfg)
        }
    }

    fun debugInfo(): List<String> = buildList {
        if(!settings.current.mInputAttributes.mIsPasswordField) {
            add("composingText = ${inputLogic.mConnection.composingTextForDebug}")
            add("committedTextBeforeComposingText = ${inputLogic.mConnection.committedTextBeforeComposingTextForDebug}")
        }
        add("LM.shouldPassThroughToLegacy = ${languageModelFacilitator.shouldPassThroughToLegacy()}")
        add("LM.isTransformerDisabledDueToTimeout = ${languageModelFacilitator.isTransformerDisabled()}")
        add("expected cursor = ${inputLogic.mConnection.mExpectedSelStart}:${inputLogic.mConnection.mExpectedSelEnd}")
        add("dictionary loaded = ${dictionaryFacilitator.hasAtLeastOneInitializedMainDictionary()}, ${!dictionaryFacilitator.hasAtLeastOneUninitializedMainDictionary()}")
        add("autoCapsFlags = ${getCurrentAutoCapsState()}")
    }

    fun debugInfoS() = debugInfo().joinToString("\n")

    @UsedForTesting
    override fun recycle() {
        inputLogic.recycle()
    }

    fun cursorStepped(steps: Int, overWords: Boolean) {
        if(!settings.current.mVibrateOn) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            helper.keyboardSwitcher.mainKeyboardView?.performHapticFeedback(
                if(overWords) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        };
    }

    override fun hasMoreTextToDelete(): Boolean =
        inputLogic.mConnection.codePointBeforeCursor != Constants.NOT_A_CODE
                || inputLogic.mWordComposer.isComposingWord

    companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        val dictionaryScope = Dispatchers.Default.limitedParallelism(1)
    }
}