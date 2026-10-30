package com.experterp.backend.service;

import org.apache.commons.csv.CSVRecord;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Column lookup by header name (case-insensitive) for Cin7 CSV imports. */
final class CsvColumns {
    private final Map<String, Integer> index = new HashMap<>();

    CsvColumns(List<String> headers) {
        for (int i = 0; i < headers.size(); i++) {
            index.putIfAbsent(headers.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
    }

    boolean has(String column) {
        return index.containsKey(column.toLowerCase(Locale.ROOT));
    }

    /** Trimmed value, or null when the column is missing or blank. */
    String get(CSVRecord r, String column) {
        Integer i = index.get(column.toLowerCase(Locale.ROOT));
        if (i == null || i >= r.size()) return null;
        String v = r.get(i).trim();
        return v.isEmpty() ? null : v;
    }
}
