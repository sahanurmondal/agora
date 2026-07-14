package com.agora.platform.snowflake;

public final class Base62 {

    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private Base62() {
    }

    public static String encode(long n) {
        if (n < 0) throw new IllegalArgumentException("negative: " + n);
        if (n == 0) return "0";
        StringBuilder sb = new StringBuilder(11);
        while (n > 0) {
            sb.append(ALPHABET[(int) (n % 62)]);
            n /= 62;
        }
        return sb.reverse().toString();
    }
}
