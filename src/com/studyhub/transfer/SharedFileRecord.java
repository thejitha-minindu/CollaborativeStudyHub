package com.studyhub.transfer;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * [MEMBER 2: TCP Sockets & NIO FileChannel]
 * Metadata record for shared study materials deposited in the server file vault.
 */
public class SharedFileRecord implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String fileId;
    private final String room;
    private final String filename;
    private final long fileSize;
    private final String sha256;
    private final String uploader;
    private final String timestamp;

    public SharedFileRecord(String fileId, String room, String filename, long fileSize, String sha256, String uploader) {
        this.fileId = fileId;
        this.room = room;
        this.filename = filename;
        this.fileSize = fileSize;
        this.sha256 = sha256;
        this.uploader = uploader;
        this.timestamp = LocalDateTime.now().format(FORMATTER);
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

    @Override
    public String toString() {
        return String.format("[%s] %s (%s, uploaded by %s)", room, filename, getFormattedSize(), uploader);
    }
}
