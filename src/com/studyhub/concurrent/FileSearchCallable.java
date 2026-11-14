package com.studyhub.concurrent;

import com.studyhub.transfer.SharedFileRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * [MEMBER 5: Multithreading & URL/HTTP Networking]
 * Asynchronous Callable worker searching through the file vault catalog
 * across rooms or filename substrings without blocking the main event thread.
 */
public class FileSearchCallable implements Callable<List<SharedFileRecord>> {

    private final List<SharedFileRecord> snapshot;
    private final String query;
    private final String targetRoom;

    public FileSearchCallable(List<SharedFileRecord> snapshot, String query, String targetRoom) {
        this.snapshot = snapshot != null ? snapshot : new ArrayList<>();
        this.query = query != null ? query.trim().toLowerCase() : "";
        this.targetRoom = targetRoom != null ? targetRoom.trim() : "";
    }

    @Override
    public List<SharedFileRecord> call() {
        List<SharedFileRecord> matches = new ArrayList<>();
        for (SharedFileRecord record : snapshot) {
            boolean roomMatch = targetRoom.isEmpty() || record.getRoom().equalsIgnoreCase(targetRoom);
            boolean queryMatch = query.isEmpty() ||
                record.getFilename().toLowerCase().contains(query) ||
                record.getUploader().toLowerCase().contains(query);

            if (roomMatch && queryMatch) {
                matches.add(record);
            }
        }
        return matches;
    }
}
