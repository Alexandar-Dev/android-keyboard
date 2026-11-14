package org.futo.inputmethod.latin.uix.actions.aicheck;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.ArrayList;
import java.util.List;

// alex: rezultat AI provere (structured output od Claude-a)
public class AiCheckResult {
    @JsonPropertyDescription("Mistakes in order of appearance. Empty if the text has no mistakes.")
    public List<Issue> issues = new ArrayList<>();

    public static class Issue {
        @JsonPropertyDescription("Exact substring copied from the text, as short as possible (usually one word).")
        public String original;

        @JsonPropertyDescription("The corrected version of that substring.")
        public String replacement;

        @JsonPropertyDescription("Very short reason (max 8 words), in the same language as the text.")
        public String explanation;

        public Issue() { }
    }

    public AiCheckResult() { }
}
