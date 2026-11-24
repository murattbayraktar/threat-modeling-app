package com.bank.tm.core.util;

import java.util.Locale;

/** Türkçe metin normalizasyonu (arama ve eşleştirme için). */
public final class Text {

    public static final Locale TR = Locale.forLanguageTag("tr-TR");

    private Text() {
    }

    /** Küçük harfe çevirir ve Türkçe karakterleri ASCII karşılıklarına indirger. */
    public static String fold(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(TR);
        StringBuilder sb = new StringBuilder(lower.length());
        for (char c : lower.toCharArray()) {
            switch (c) {
                case 'ı' -> sb.append('i');
                case 'ğ' -> sb.append('g');
                case 'ü' -> sb.append('u');
                case 'ş' -> sb.append('s');
                case 'ö' -> sb.append('o');
                case 'ç' -> sb.append('c');
                case 'â' -> sb.append('a');
                case 'î' -> sb.append('i');
                case 'û' -> sb.append('u');
                case '̇' -> { /* "İ".toLowerCase(tr) birleştirici nokta üretmez ama güvenlik için atlanır */ }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Boşlukları tekilleştirip kırpar. */
    public static String squash(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
