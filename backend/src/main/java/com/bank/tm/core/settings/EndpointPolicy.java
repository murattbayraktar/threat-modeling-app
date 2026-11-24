package com.bank.tm.core.settings;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Ekrandan girilen LLM / embedding adreslerinin denetimi. Her zaman adres biçimi (http/https, host) doğrulanır.
 * blockPublic=true (tm.security.block-public-endpoints) ise ek olarak yalnızca kurum içi hostlara izin verilir:
 * localhost, özel IP aralıkları ve tm.security.internal-host-suffixes. PoC için varsayılan kapalıdır; dış adresler
 * serbesttir ve arayüzde "dış erişim" olarak işaretlenir. Canlıda yapılandırmadan açılabilir.
 */
public final class EndpointPolicy {

    private final boolean blockPublic;
    private final List<String> internalSuffixes;

    public EndpointPolicy(boolean blockPublic, List<String> internalSuffixes) {
        this.blockPublic = blockPublic;
        this.internalSuffixes = internalSuffixes == null ? List.of()
                : internalSuffixes.stream().map(s -> s.toLowerCase(Locale.ROOT).strip()).filter(s -> !s.isEmpty()).toList();
    }

    public static EndpointPolicy permissive() {
        return new EndpointPolicy(false, List.of());
    }

    public boolean blockPublic() { return blockPublic; }

    public List<String> internalSuffixes() { return internalSuffixes; }

    /** Adres kurala aykırıysa açıklayıcı bir hata fırlatır; boş adres kontrol edilmez. */
    public void check(String label, String url) {
        if (url == null || url.isBlank()) return;
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + ": geçersiz adres (" + url + ")");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException(label + ": adres http:// veya https:// ile başlamalı");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException(label + ": adreste host yok");
        if (blockPublic && !isInternal(host)) {
            throw new IllegalArgumentException(label + ": '" + host + "' kurum içi bir adres olarak tanınmadı. "
                    + "Bulut erişimine izin verilmiyor. Adres kurum içiyse yöneticiniz tm.security.internal-host-suffixes "
                    + "listesine alan adını eklemeli (şu an: " + (internalSuffixes.isEmpty() ? "boş" : String.join(", ", internalSuffixes)) + ").");
        }
    }

    public boolean isInternal(String hostRaw) {
        String host = hostRaw.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.equals("localhost") || host.endsWith(".localhost")) return true;
        if (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            String[] p = host.split("\\.");
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254);
        }
        if (host.contains(":")) { // IPv6
            return host.equals("::1") || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80");
        }
        if (!host.contains(".")) return true; // tek etiketli iç ağ adları (ör. genai-gw)
        for (String s : internalSuffixes) {
            String suffix = s.startsWith(".") ? s : "." + s;
            if (host.endsWith(suffix) || host.equals(s.startsWith(".") ? s.substring(1) : s)) return true;
        }
        return false;
    }
}
