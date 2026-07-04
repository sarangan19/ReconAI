package com.reconai.matching;

public final class JaroWinkler {

    private JaroWinkler() {}

    public static double similarity(String s, String t) {
        if (s == null || t == null) return 0.0;
        if (s.equals(t)) return 1.0;
        int sl = s.length(), tl = t.length();
        if (sl == 0 || tl == 0) return 0.0;

        int matchDist = Math.max(sl, tl) / 2 - 1;
        if (matchDist < 0) matchDist = 0;

        boolean[] sMatch = new boolean[sl];
        boolean[] tMatch = new boolean[tl];
        int matches = 0;

        for (int i = 0; i < sl; i++) {
            int lo = Math.max(0, i - matchDist);
            int hi = Math.min(i + matchDist + 1, tl);
            for (int j = lo; j < hi; j++) {
                if (tMatch[j] || s.charAt(i) != t.charAt(j)) continue;
                sMatch[i] = tMatch[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0.0;

        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < sl; i++) {
            if (!sMatch[i]) continue;
            while (!tMatch[k]) k++;
            if (s.charAt(i) != t.charAt(k)) transpositions++;
            k++;
        }

        double jaro = (matches / (double) sl
                     + matches / (double) tl
                     + (matches - transpositions / 2.0) / matches) / 3.0;

        // Winkler prefix bonus (max 4 chars, scale = 0.1)
        int prefix = 0;
        int limit = Math.min(Math.min(sl, tl), 4);
        while (prefix < limit && s.charAt(prefix) == t.charAt(prefix)) prefix++;

        return jaro + prefix * 0.1 * (1.0 - jaro);
    }
}
