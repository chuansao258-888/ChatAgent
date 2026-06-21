package com.yulong.chatagent.agent.deepthink;

import java.util.regex.Pattern;

final class DeepThinkLanguageSupport {

    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u9fff]");
    private static final Pattern LATIN_WORD = Pattern.compile("\\b[A-Za-z]{2,}\\b");

    private DeepThinkLanguageSupport() {
    }

    static boolean prefersChinese(String text) {
        int cjkCount = countMatches(CJK, text);
        int latinWordCount = countMatches(LATIN_WORD, text);
        return cjkCount >= 4 && cjkCount >= latinWordCount;
    }

    static String choose(String languageSource, String english, String chinese) {
        return prefersChinese(languageSource) ? chinese : english;
    }

    static String stepLanguageSource(DeepThinkPlanStep step, String planGoal) {
        if (step == null) {
            return planGoal == null ? "" : planGoal;
        }
        return safe(planGoal) + " " + safe(step.getTitle()) + " " + safe(step.getObjective());
    }

    static String planLanguageSource(DeepThinkPlan plan) {
        if (plan == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(safe(plan.getGoal()));
        if (plan.getSteps() != null) {
            for (DeepThinkPlanStep step : plan.getSteps()) {
                sb.append(' ')
                        .append(safe(step.getTitle()))
                        .append(' ')
                        .append(safe(step.getObjective()));
            }
        }
        return sb.toString();
    }

    private static int countMatches(Pattern pattern, String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
