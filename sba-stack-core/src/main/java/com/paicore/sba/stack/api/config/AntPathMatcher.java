package com.paicore.sba.stack.api.config;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/** Ant-style path matcher (?, *, **) backing {@link SecurityConfig#isPathBypassed(String)}. */
final class AntPathMatcher {

    private static final ConcurrentMap<String, Pattern> CACHE = new ConcurrentHashMap<>();
    private static final String REGEX_META = "\\.+(){}|^$[]";

    private AntPathMatcher() {
    }

    static boolean matches(String pattern, String path) {
        if (pattern == null || path == null) {
            return false;
        }
        if (pattern.equals(path)) {
            return true;
        }
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return CACHE.computeIfAbsent(pattern, AntPathMatcher::compile).matcher(path).matches();
    }

    private static Pattern compile(String pattern) {
        StringBuilder regex = new StringBuilder(pattern.length() + 8).append('^');
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i += 2;
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else if (REGEX_META.indexOf(c) >= 0) {
                regex.append('\\').append(c);
                i++;
            } else {
                regex.append(c);
                i++;
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }
}
