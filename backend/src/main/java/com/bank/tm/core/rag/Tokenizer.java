package com.bank.tm.core.rag;

import com.bank.tm.core.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Türkçe/İngilizce karışık metin için basit tokenizer: küçük harf + Türkçe karakter katlama + durak kelime
 * eleme + önek kökleme (ilk 6 karakter). Türkçenin eklemeli yapısında "doğrulama/doğrulaması/doğrulanmadı"
 * gibi biçimleri aynı köke indirger.
 */
public final class Tokenizer {

    private static final int STEM = 6;
    private static final Set<String> STOP = Set.of("ve", "veya", "ile", "bir", "bu", "su", "o", "icin", "da", "de",
            "ki", "mi", "gibi", "olan", "olarak", "her", "en", "daha", "cok", "ya", "ne", "the", "and", "or", "of",
            "to", "in", "for", "on", "a", "an", "is", "are", "be", "by", "with", "as", "at", "from");

    private Tokenizer() {
    }

    public static List<String> tokens(String text) {
        String folded = Text.fold(text).replaceAll("[^a-z0-9]+", " ");
        List<String> out = new ArrayList<>();
        for (String t : folded.split(" ")) {
            if (t.length() < 2 || STOP.contains(t)) continue;
            out.add(t.length() > STEM ? t.substring(0, STEM) : t);
        }
        return out;
    }
}
