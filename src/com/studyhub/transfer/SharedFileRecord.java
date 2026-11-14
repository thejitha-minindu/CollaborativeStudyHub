package com.studyhub.transfer;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * [MEMBER 2: TCP Sockets & NIO FileChannel]
 * Metadata record for shared study materials deposited in the server file vault.
 *
 * <p>Every record now also carries the physical {@code storedFilename} used inside
 * {@code shared_storage/<room>/} so duplicate uploads of the same display name
 * (e.g. {@code notes.pdf}, {@code notes (1).pdf}) can be told apart, plus the
 * upload timestamp so the vault index survives a server restart.</p>
 */
public class SharedFileRecord implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String fileId;
    private final String room;
    private final String filename;
    private final String storedFilename;
    private final long fileSize;
    private final String sha256;
    private final String uploader;
    private final String timestamp;

    public SharedFileRecord(String fileId, String room, String filename, long fileSize, String sha256, String uploader) {
        this(fileId, room, filename, filename, fileSize, sha256, uploader, LocalDateTime.now().format(FORMATTER));
    }

    public SharedFileRecord(String fileId, String room, String filename, String storedFilename,
                            long fileSize, String sha256, String uploader) {
        this(fileId, room, filename, storedFilename, fileSize, sha256, uploader, LocalDateTime.now().format(FORMATTER));
    }

    public SharedFileRecord(String fileId, String room, String filename, String storedFilename,
                            long fileSize, String sha256, String uploader, String timestamp) {
        this.fileId = fileId;
        this.room = room;
        this.filename = filename;
        this.storedFilename = (storedFilename == null || storedFilename.isEmpty()) ? filename : storedFilename;
        this.fileSize = fileSize;
        this.sha256 = sha256;
        this.uploader = uploader;
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now().format(FORMATTER);
    }

    public String getFileId() {
        return fileId;
    }

    public String getRoom() {
        return room;
    }

    public String getFilename() {
        return filename;
    }

    /**
     * Physical file name inside {@code shared_storage/<room>/}.
     * Equals {@link #getFilename()} for the first upload of a name, and
     * {@code name (1).ext} style variants for later duplicates.
     */
    public String getStoredFilename() {
        return storedFilename;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getSha256() {
        return sha256;
    }

    public String getUploader() {
        return uploader;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getFormattedSize() {
        if (fileSize < 1024) return fileSize + " B";
        if (fileSize < 1024 * 1024) return String.format("%.1f KB", fileSize / 1024.0);
        return String.format("%.2f MB", fileSize / (1024.0 * 1024.0));
    }

    /** Short lower-case extension (e.g. "pdf", "zip", "java") used for file type icons. */
    public String getExtension() {
        int dot = filename.lastIndexOf('.');
        return (dot >= 0 && dot < filename.length() - 1) ? filename.substring(dot + 1).toLowerCase() : "";
    }

    @Override
    public String toString() {
        return String.format("[%s] %s (%s, uploaded by %s)", room, filename, getFormattedSize(), uploader);
    }
}
