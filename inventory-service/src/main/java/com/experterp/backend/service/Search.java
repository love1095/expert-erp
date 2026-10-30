package com.experterp.backend.service;

import java.util.Locale;

/** Free-text list filters. */
final class Search {
    private Search() {}

    // Lower-case "contains" LIKE pattern for q; '' when there is nothing to search for (queries treat '' as "any").
    // LIKE wildcards typed by the user are matched literally.
    static String pattern(String q) {
        if (q == null || q.isBlank()) return "";
        String s = q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + s + "%";
    }
}
