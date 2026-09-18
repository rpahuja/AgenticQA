package com.agenticqa.rag;

import java.util.Locale;

public final class TextNormalizer {

    private TextNormalizer() {
        // Utility class.
    }

    
    public static String normalize(String text) {

        if (text == null || text.isEmpty()) {
            return "";
        }

        String value = text;

        value = value.replace('_', ' ');
        value = value.replace('-', ' ');

        value = value.replaceAll(
                "([a-z0-9])([A-Z])",
                "$1 $2"
        );

        
        value = value.toLowerCase(Locale.ROOT);

        
        value = value.replaceAll(
                "[^a-z0-9]+",
                " "
        );

        value = value.trim().replaceAll(
                "\\s+",
                " "
        );

        return value;
    }
}