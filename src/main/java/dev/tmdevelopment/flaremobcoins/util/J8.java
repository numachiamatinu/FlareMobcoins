package dev.tmdevelopment.flaremobcoins.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small helpers missing in Java 8. */
public final class J8 {

    private J8() {
    }

    @SafeVarargs
    public static <T> java.util.List<T> list(T... values) {
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(java.util.Arrays.asList(values)));
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    public static void write(Path path, String text, Charset charset) throws IOException {
        Files.write(path, text.getBytes(charset));
    }

    public static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
