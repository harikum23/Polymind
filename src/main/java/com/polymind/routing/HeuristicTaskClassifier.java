package com.polymind.routing;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Fast, free heuristic classifier (ARCHITECTURE.md §4.3 step 2):
 * code fences / import statements -> CODE; math symbols / keywords -> MATH; otherwise CHAT.
 * An LLM/embedding classifier is a later, pluggable upgrade (see docs/future-pending.md).
 */
@Component
public class HeuristicTaskClassifier implements TaskClassifier {

    private static final Pattern CODE_FENCE = Pattern.compile("```|~~~");
    private static final Pattern CODE_HINTS = Pattern.compile(
            "(?m)^\\s*(import |from \\w|#include|package |public class|def |func |class |const |let |var |fn |using )"
                    + "|=>|;\\s*$|\\b(npm|pip|gradle|maven|docker)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MATH_SYMBOLS = Pattern.compile("[∫∑√π±×÷≤≥≠]|\\b\\d+\\s*[+\\-*/^]\\s*\\d+");
    private static final Pattern MATH_KEYWORDS = Pattern.compile(
            "\\b(solve|derivative|integral|equation|theorem|matrix|probability|calculus|algebra|"
                    + "factor|simplify|prove|polynomial|logarithm)\\b",
            Pattern.CASE_INSENSITIVE);

    @Override
    public TaskCategory classify(List<String> userMessages) {
        String text = String.join("\n", userMessages);
        if (text.isBlank()) {
            return TaskCategory.CHAT;
        }
        if (CODE_FENCE.matcher(text).find() || CODE_HINTS.matcher(text).find()) {
            return TaskCategory.CODE;
        }
        if (MATH_SYMBOLS.matcher(text).find() || MATH_KEYWORDS.matcher(text).find()) {
            return TaskCategory.MATH;
        }
        return TaskCategory.CHAT;
    }
}
