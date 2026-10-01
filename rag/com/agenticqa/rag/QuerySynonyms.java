package com.agenticqa.rag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QuerySynonyms {

    private QuerySynonyms() {
    }

    public static Map<String, List<String>> defaults() {
        Map<String, List<String>> synonyms = new LinkedHashMap<>();

        synonyms.put("login", List.of("sign in"));
        synonyms.put("logout", List.of("sign out"));
        synonyms.put("password", List.of("passcode"));
        synonyms.put("passcode", List.of("password"));

        return Collections.unmodifiableMap(synonyms);
    }
}