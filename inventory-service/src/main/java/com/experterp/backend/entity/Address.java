package com.experterp.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A postal address in Cin7's fields (phase G4): Line 1, Line 2, City / Suburb, State / Province, Zip / Postcode,
 * Country. Kept on customers (their address book) and copied onto sale orders (billing and shipping).
 */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Address {

    @Column(length = 255)
    private String line1;

    @Column(length = 255)
    private String line2;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    @Column(length = 30)
    private String postcode;

    @Column(length = 100)
    private String country;

    public Address(Address a) {
        this(a.line1, a.line2, a.city, a.state, a.postcode, a.country);
    }

    public boolean isEmpty() {
        return blank(line1) && blank(line2) && blank(city) && blank(state) && blank(postcode) && blank(country);
    }

    /** The lines a letter shows: line 1, line 2, then city, state and postcode together, then the country. */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        add(out, line1);
        add(out, line2);
        String town = String.join(" ", List.of(nz(city), nz(state), nz(postcode)).stream().filter(s -> !s.isEmpty()).toList());
        add(out, town);
        add(out, country);
        return out;
    }

    /** One line, comma separated (lists, audit). */
    public String oneLine() {
        return String.join(", ", lines());
    }

    /** Blanks become null, the rest trimmed; null when nothing is left. */
    public static Address clean(Address a) {
        if (a == null) return null;
        Address c = new Address(t(a.line1), t(a.line2), t(a.city), t(a.state), t(a.postcode), t(a.country));
        return c.isEmpty() ? null : c;
    }

    private static String t(String s) {
        return blank(s) ? null : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return blank(s) ? "" : s.trim();
    }

    private static void add(List<String> out, String s) {
        if (!blank(s)) out.add(s.trim());
    }
}
