package com.agentflow.rag.retrieval;

import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.Locale;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** Bounded local postings; query ranking is added by the retrieval phase. */
public final class KeywordIndex {
    private static final Pattern TOKENS = Pattern.compile("[a-z0-9_]+|\\p{IsHan}+");
    private final Map<String, Set<String>> postings;
    private final int postingCount;

    public KeywordIndex(Map<String, String> chunks) {
        var building = new HashMap<String, Set<String>>();
        int count = 0;
        for (var chunk : chunks.entrySet()) {
            for (String term : terms(chunk.getValue())) {
                if (building.computeIfAbsent(term, ignored -> new HashSet<>()).add(chunk.getKey())) { count++; }
                if (building.size() > 250_000 || count > 500_000) {
                    throw new IllegalArgumentException("KEYWORD_INDEX_LIMIT");
                }
            }
        }
        building.replaceAll((term, ids) -> Set.copyOf(ids));
        this.postings = Map.copyOf(building);
        this.postingCount = count;
    }

    public int termCount() { return postings.size(); }
    public int postingCount() { return postingCount; }

    public static Set<String> terms(String text) {
        var result = new LinkedHashSet<String>();
        var matcher = TOKENS.matcher(Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (Character.UnicodeScript.of(token.codePointAt(0)) == Character.UnicodeScript.HAN) {
                int[] points = token.codePoints().toArray();
                if (points.length == 1) { result.add(token); }
                for (int i = 0; i < points.length - 1; i++) { result.add(new String(points, i, 2)); }
            } else if (token.length() <= 64) {
                result.add(token);
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
