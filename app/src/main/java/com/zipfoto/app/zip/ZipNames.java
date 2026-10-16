package com.zipfoto.app.zip;

import java.util.HashSet;
import java.util.Locale;

/** Garantisce nomi univoci nell'archivio (anche ignorando maiuscole/minuscole, come su Windows). */
public final class ZipNames {
    private final HashSet<String> used = new HashSet<>();

    /** Es.: "IMG_1.jpg" -> "IMG_1.jpg", poi "IMG_1 (2).jpg", "IMG_1 (3).jpg"... */
    public String unique(String path) {
        if (used.add(path.toLowerCase(Locale.ROOT))) {
            return path;
        }
        int slash = path.lastIndexOf('/');
        String dir = slash >= 0 ? path.substring(0, slash + 1) : "";
        String file = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = file.lastIndexOf('.');
        String base = dot > 0 ? file.substring(0, dot) : file;
        String ext = dot > 0 ? file.substring(dot) : "";
        int i = 2;
        while (true) {
            String candidate = dir + base + " (" + i + ")" + ext;
            if (used.add(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
            i++;
        }
    }
}
