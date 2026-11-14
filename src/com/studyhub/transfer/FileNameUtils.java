package com.studyhub.transfer;

import java.io.File;

/**
 * [MEMBER 2: TCP Sockets & NIO FileChannel]
 * Filename helpers shared by the transfer server (vault side) and the Swing client
 * (downloads side) so duplicate uploads / downloads never silently overwrite each other.
 */
public final class FileNameUtils {

    private FileNameUtils() {}

    /** Strips any path information so a crafted upload can never escape its room directory. */
    public static String sanitizeName(String name) {
        if (name == null || name.trim().isEmpty()) return "untitled";
        String clean = new File(name.trim()).getName();
        clean = clean.replace("\\", "_").replace("/", "_").replace(":", "_");
        return clean.isEmpty() ? "untitled" : clean;
    }

    /**
     * Returns a non-clashing {@code File} inside {@code dir} for the given display name,
     * producing {@code notes.pdf}, {@code notes (1).pdf}, {@code notes (2).pdf} ...
     */
    public static File uniqueFile(File dir, String name) {
        String safeName = sanitizeName(name);
        File candidate = new File(dir, safeName);
        if (!candidate.exists()) {
            return candidate;
        }

        String base = safeName;
        String extension = "";
        int dot = safeName.lastIndexOf('.');
        if (dot > 0) {
            base = safeName.substring(0, dot);
            extension = safeName.substring(dot);
        }

        for (int i = 1; i <= 9999; i++) {
            candidate = new File(dir, base + " (" + i + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + extension);
    }
}
