package com.chillednems.ikemenlab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Conservative name/author cues adapted from the macOS TagDetector. */
final class TagInference {
    private TagInference() { }

    static List<String> from(String reference, String name, String author) {
        String text = ((reference == null ? "" : reference) + " " + (name == null ? "" : name))
                .toLowerCase(Locale.ROOT);
        String maker = author == null ? "" : author.toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();
        if (cue(text, "street fighter") || cue(text, "sf2") || cue(text, "sf3") || cue(text, "sf4")) tags.add("Street Fighter");
        if (cue(text, "king of fighters") || cue(text, "kof")) tags.add("KOF");
        if (cue(text, "marvel vs capcom") || cue(text, "mvc")) tags.add("MVC");
        if (cue(text, "capcom vs snk") || cue(text, "cvs")) tags.add("CVS");
        if (cue(text, "guilty gear") || cue(text, "ggxx") || cue(text, "ggxrd")) tags.add("Guilty Gear");
        if (cue(text, "melty blood") || cue(text, "mbaa")) tags.add("Melty Blood");
        if (cue(text, "marvel") || cue(text, "x-men") || cue(text, "wolverine") || cue(text, "spider-man")) tags.add("Marvel");
        if (cue(maker, "pots")) tags.add("POTS Style");
        if (cue(maker, "infinite")) tags.add("Infinite Style");
        if (cue(text, "cvs style") || cue(maker, "cvs style")) tags.add("CVS Style");
        if (cue(text, "mvc style") || cue(maker, "mvc style")) tags.add("MVC Style");
        Collections.sort(tags);
        return tags;
    }

    private static boolean cue(String text, String phrase) {
        for (int from = 0; from < text.length();) {
            int at = text.indexOf(phrase, from);
            if (at < 0) return false;
            int end = at + phrase.length();
            if ((at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1)))
                    && (end == text.length() || !Character.isLetterOrDigit(text.charAt(end)))) return true;
            from = at + 1;
        }
        return false;
    }
}
