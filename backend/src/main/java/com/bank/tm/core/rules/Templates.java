package com.bank.tm.core.rules;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "{element.name} üzerinde ..." gibi şablonları fact değerleriyle doldurur. */
public final class Templates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_.]+)}");

    private Templates() {
    }

    public static String render(String template, Map<String, Object> facts) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = facts.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "?" : v.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
