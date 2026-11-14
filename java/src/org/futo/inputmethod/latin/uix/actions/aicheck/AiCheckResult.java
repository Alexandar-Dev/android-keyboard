package org.futo.inputmethod.latin.uix.actions.aicheck;


import java.util.ArrayList;
import java.util.List;

// alex: rezultat AI provere (structured output od Claude-a)
public class AiCheckResult {
    public List<Issue> issues = new ArrayList<>();

    public static class Issue {
        public String original;

        public String replacement;

        public String explanation;

        public Issue() { }
    }

    public AiCheckResult() { }
}
