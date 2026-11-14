package com.studyhub.common;

import com.studyhub.security.CryptoIntegrityService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * [DISK PERSISTENCE ENGINE - java.nio.file]
 * Lightweight, thread-safe, zero-dependency flat-file storage engine that keeps the
 * Collaborative Study Hub durable across server restarts.
 *
 * <p>Directory layout (created lazily under {@code data/}):</p>
 * <ul>
 *   <li>{@code users.db}          - {@code username|salt:salted_sha256_hash|role|displayName}</li>
 *   <li>{@code rooms.db}          - {@code room|topic|pinnedPayload} (custom rooms survive restarts)</li>
 *   <li>{@code history_<room>.log} - append-only room transcript (one encoded line per event)</li>
 *   <li>{@code vault_index.db}    - {@code fileId|room|uploader|original|stored|size|sha256|timestamp}</li>
 * </ul>
 *
 * <p>All mutations update the in-memory mirror first (guarded by a fair
 * {@link ReentrantReadWriteLock}) and are then written to disk by a background flusher.
 * {@link #flush()} forces every pending write to disk and is invoked from the
 * StudyHub shutdown hook so no state is ever lost. Flat files are re-written atomically
 * (temp file + {@code ATOMIC_MOVE}) so a crash mid-write can never corrupt an index.</p>
 */
public final class DataPersistenceManager {

    // ------------------------------------------------------------------
    // File / directory constants
    // ------------------------------------------------------------------
    public static final String DATA_DIR_NAME = "data";
    public static final String USERS_DB_FILE = "users.db";
    public static final String ROOMS_DB_FILE = "rooms.db";
    public static final String VAULT_INDEX_FILE = "vault_index.db";
    public static final String HISTORY_FILE_PREFIX = "history_";
    public static final String HISTORY_FILE_SUFFIX = ".log";
    public static final int HISTORY_REPLAY_LIMIT = 50;
    private static final String TMP_SUFFIX = ".tmp";
    private static final long FLUSH_INTERVAL_MS = 400L;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static volatile DataPersistenceManager singleton;

    // ------------------------------------------------------------------
    // Persisted record types
    // ------------------------------------------------------------------

    /** One registered account row of {@code users.db}. */
    public static final class UserRecord {
        private final String username;
        private final String credential;   // salt + ":" + sha256(salt:clientSha256)
        private final String role;
        private final String displayName;

        public UserRecord(String username, String credential, String role, String displayName) {
            this.username = username;
            this.credential = credential;
            this.role = role;
            this.displayName = displayName;
        }

        public String getUsername() { return username; }
        public String getCredential() { return credential; }
        public String getRole() { return role; }
        public String getDisplayName() { return displayName; }

        public String getSalt() {
            int idx = credential == null ? -1 : credential.indexOf(':');
            return idx > 0 ? credential.substring(0, idx) : "";
        }

        public String getSaltedHash() {
            int idx = credential == null ? -1 : credential.indexOf(':');
            return idx >= 0 ? credential.substring(idx + 1) : credential;
        }

        /** Wire/storage line: {@code username|credential|role|displayName}. */
        public String toLine() {
            return join(encodeField(username), credential, encodeField(role), encodeField(displayName));
        }

        public static UserRecord fromLine(String line) {
            String[] f = split(line);
            if (f.length < 4) return null;
            return new UserRecord(decodeField(f[0]), f[1], decodeField(f[2]), decodeField(f[3]));
        }
    }

    /** One room row of {@code rooms.db}. */
    public static final class RoomRecord {
        private final String name;
        private String topic;
        private String pinnedPayload;

        public RoomRecord(String name, String topic, String pinnedPayload) {
            this.name = name;
            this.topic = topic != null ? topic : "";
            this.pinnedPayload = pinnedPayload != null ? pinnedPayload : "";
        }

        public String getName() { return name; }
        public String getTopic() { return topic; }
        public String getPinnedPayload() { return pinnedPayload; }
        public void setTopic(String topic) { this.topic = topic != null ? topic : ""; }
        public void setPinnedPayload(String pinnedPayload) { this.pinnedPayload = pinnedPayload != null ? pinnedPayload : ""; }
        public boolean hasPin() { return !pinnedPayload.isEmpty(); }

        public String toLine() {
            return join(encodeField(name), encodeField(topic), encodeField(pinnedPayload));
        }

        public static RoomRecord fromLine(String line) {
            String[] f = split(line);
            if (f.length < 2) return null;
            return new RoomRecord(decodeField(f[0]), decodeField(f[1]), f.length > 2 ? decodeField(f[2]) : "");
        }
    }

    /** One row of {@code vault_index.db} describing a file inside {@code shared_storage/}. */
    public static final class VaultEntry {
        private final String fileId;
        private final String room;
        private final String uploader;
        private final String originalFilename;
        private final String storedFilename;
        private final long fileSize;
        private final String sha256;
        private final String timestamp;

        public VaultEntry(String fileId, String room, String uploader, String originalFilename,
                          String storedFilename, long fileSize, String sha256, String timestamp) {
            this.fileId = fileId;
            this.room = room;
            this.uploader = uploader;
            this.originalFilename = originalFilename;
            this.storedFilename = storedFilename;
            this.fileSize = fileSize;
            this.sha256 = sha256;
            this.timestamp = timestamp;
        }

        public String getFileId() { return fileId; }
        public String getRoom() { return room; }
        public String getUploader() { return uploader; }
        public String getOriginalFilename() { return originalFilename; }
        public String getStoredFilename() { return storedFilename; }
        public long getFileSize() { return fileSize; }
        public String getSha256() { return sha256; }
        public String getTimestamp() { return timestamp; }

        public String toLine() {
            return join(encodeField(fileId), encodeField(room), encodeField(uploader), encodeField(originalFilename),
                encodeField(storedFilename), String.valueOf(fileSize), encodeField(sha256), encodeField(timestamp));
        }

        public static VaultEntry fromLine(String line) {
            String[] f = split(line);
            if (f.length < 8) return null;
            long size = 0L;
            try {
                size = Long.parseLong(f[5]);
            } catch (NumberFormatException ignored) {
                size = 0L;
            }
            return new VaultEntry(decodeField(f[0]), decodeField(f[1]), decodeField(f[2]), decodeField(f[3]),
                decodeField(f[4]), size, decodeField(f[6]), decodeField(f[7]));
        }
    }

    /** One append-only line of {@code history_<room>.log}. */
    public static final class HistoryEntry {
        private final String kind;       // MSG | LINK | FILE | SYSTEM
        private final String timestamp;
        private final String sender;
        private final String role;
        private final String encFlag;    // PLAIN | AES
        private final String payload;    // message text, or link/file metadata
        private final String replyRef;   // quoted "sender|snippet" (may be empty)

        public HistoryEntry(String kind, String timestamp, String sender, String role,
                            String encFlag, String payload, String replyRef) {
            this.kind = kind != null ? kind : ProtocolConstants.HIST_MSG;
            this.timestamp = timestamp != null ? timestamp : "";
            this.sender = sender != null ? sender : "";
            this.role = role != null ? role : ProtocolConstants.ROLE_STUDENT;
            this.encFlag = encFlag != null ? encFlag : ProtocolConstants.ENC_PLAIN;
            this.payload = payload != null ? payload : "";
            this.replyRef = replyRef != null ? replyRef : "";
        }

        public String getKind() { return kind; }
        public String getTimestamp() { return timestamp; }
        public String getSender() { return sender; }
        public String getRole() { return role; }
        public String getEncFlag() { return encFlag; }
        public String getPayload() { return payload; }
        public String getReplyRef() { return replyRef; }

        public String toLine() {
            return join(encodeField(kind), encodeField(timestamp), encodeField(sender), encodeField(role),
                encodeField(encFlag), encodeField(payload), encodeField(replyRef));
        }

        public static HistoryEntry fromLine(String line) {
            String[] f = split(line);
            if (f.length < 6) return null;
            return new HistoryEntry(decodeField(f[0]), decodeField(f[1]), decodeField(f[2]), decodeField(f[3]),
                decodeField(f[4]), decodeField(f[5]), f.length > 6 ? decodeField(f[6]) : "");
        }
    }

    // ------------------------------------------------------------------
    // Instance state
    // ------------------------------------------------------------------
    private final Path dataDir;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Map<String, UserRecord> users = new LinkedHashMap<>();
    private final Map<String, RoomRecord> rooms = new LinkedHashMap<>();
    private final List<VaultEntry> vault = new ArrayList<>();
    private final ConcurrentLinkedQueue<PendingHistory> historyQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean usersDirty = new AtomicBoolean(false);
    private final AtomicBoolean roomsDirty = new AtomicBoolean(false);
    private final AtomicBoolean vaultDirty = new AtomicBoolean(false);
    private final ScheduledExecutorService flusher;
    private volatile boolean initialized = false;
    private volatile boolean closed = false;

    private static final class PendingHistory {
        final String room;
        final String line;

        PendingHistory(String room, String line) {
            this.room = room;
            this.line = line;
        }
    }

    private DataPersistenceManager(Path dataDir) {
        this.dataDir = dataDir;
        this.flusher = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "StudyHub-Persistence-Flusher");
                t.setDaemon(true);
                return t;
            }
        });
    }

    // ------------------------------------------------------------------
    // Singleton + lifecycle
    // ------------------------------------------------------------------

    /** Returns the process-wide engine rooted at {@code data/}, initialising it on first use. */
    public static DataPersistenceManager getInstance() {
        if (singleton == null) {
            synchronized (DataPersistenceManager.class) {
                if (singleton == null) {
                    DataPersistenceManager created = new DataPersistenceManager(Paths.get(DATA_DIR_NAME));
                    created.initialize();
                    singleton = created;
                }
            }
        }
        return singleton;
    }

    /**
     * Creates (and initialises) an independent engine rooted at the given directory.
     * Used to prove that state reloads correctly after a "server restart".
     */
    public static DataPersistenceManager forDirectory(Path directory) {
        DataPersistenceManager manager = new DataPersistenceManager(directory);
        manager.initialize();
        return manager;
    }

    /** Creates {@code data/} plus every index file and loads existing state into memory. */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        try {
            Files.createDirectories(dataDir);
            touchFile(resolve(USERS_DB_FILE));
            touchFile(resolve(ROOMS_DB_FILE));
            touchFile(resolve(VAULT_INDEX_FILE));
            loadFromDisk();
            initialized = true;
            flusher.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        flush();
                    } catch (RuntimeException ignored) {
                        // Background flush must never kill the flusher thread.
                    }
                }
            }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialise StudyHub persistence at " + dataDir, e);
        }
    }

    public Path getDataDirectory() {
        return dataDir;
    }

    public boolean isInitialized() {
        return initialized;
    }

    private void loadFromDisk() throws IOException {
        lock.writeLock().lock();
        try {
            users.clear();
            for (String line : readLines(resolve(USERS_DB_FILE))) {
                if (line.trim().isEmpty()) continue;
                UserRecord record = UserRecord.fromLine(line);
                if (record != null && !record.getUsername().isEmpty()) {
                    users.put(record.getUsername().toLowerCase(), record);
                }
            }

            rooms.clear();
            for (String line : readLines(resolve(ROOMS_DB_FILE))) {
                if (line.trim().isEmpty()) continue;
                RoomRecord record = RoomRecord.fromLine(line);
                if (record != null && !record.getName().isEmpty()) {
                    rooms.put(record.getName().toLowerCase(), record);
                }
            }
            if (rooms.isEmpty()) {
                for (String defaultRoom : ProtocolConstants.DEFAULT_ROOMS) {
                    rooms.put(defaultRoom.toLowerCase(), new RoomRecord(defaultRoom, defaultRoomTopic(defaultRoom), ""));
                }
                roomsDirty.set(true);
            }

            vault.clear();
            for (String line : readLines(resolve(VAULT_INDEX_FILE))) {
                if (line.trim().isEmpty()) continue;
                VaultEntry entry = VaultEntry.fromLine(line);
                if (entry != null) {
                    vault.add(entry);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Human friendly default topic used when a stock room is seeded for the first time. */
    public static String defaultRoomTopic(String room) {
        String name = room == null ? "" : room.replace("#", "");
        if ("general".equalsIgnoreCase(name)) return "General study discussion and resource sharing";
        if ("network-programming".equalsIgnoreCase(name)) return "Sockets, NIO selectors, framing and protocols";
        if ("algorithms".equalsIgnoreCase(name)) return "Complexity analysis, graphs and dynamic programming";
        if ("exam-prep".equalsIgnoreCase(name)) return "Past papers, exam timetables and revision drills";
        return "Study discussions and resources for #" + name;
    }

    // ------------------------------------------------------------------
    // Accounts (data/users.db)
    // ------------------------------------------------------------------

    public List<UserRecord> getUsers() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(users.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public UserRecord getUser(String username) {
        if (username == null) return null;
        lock.readLock().lock();
        try {
            return users.get(username.toLowerCase());
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean hasUser(String username) {
        return getUser(username) != null;
    }

    public int getUserCount() {
        lock.readLock().lock();
        try {
            return users.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public String getDisplayName(String username) {
        UserRecord record = getUser(username);
        return record != null ? record.getDisplayName() : username;
    }

    /**
     * Registers a brand new account using the client supplied SHA-256 digest.
     * The digest is never stored verbatim - it is re-salted per account first.
     *
     * @return {@code true} when the account was created, {@code false} when the username is taken.
     */
    public boolean registerUser(String username, String clientSha256, String role, String displayName) {
        if (username == null || username.trim().isEmpty()) return false;
        String key = username.trim().toLowerCase();
        lock.writeLock().lock();
        try {
            if (users.containsKey(key)) {
                return false;
            }
            String salt = generateSalt();
            String credential = salt + ":" + hashCredential(salt, clientSha256);
            String resolvedRole = (role == null || role.isEmpty()) ? ProtocolConstants.ROLE_STUDENT : role;
            String resolvedName = (displayName == null || displayName.trim().isEmpty()) ? username.trim() : displayName.trim();
            users.put(key, new UserRecord(username.trim(), credential, resolvedRole, resolvedName));
            usersDirty.set(true);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Verifies a login attempt against the salted credential on disk. */
    public boolean credentialsMatch(String username, String clientSha256) {
        UserRecord record = getUser(username);
        if (record == null || clientSha256 == null) return false;
        String candidate = hashCredential(record.getSalt(), clientSha256);
        return constantTimeEquals(candidate, record.getSaltedHash());
    }

    /** Updates display name / role for an existing account. */
    public boolean updateProfile(String username, String displayName, String role) {
        String key = username == null ? null : username.toLowerCase();
        lock.writeLock().lock();
        try {
            UserRecord existing = users.get(key);
            if (existing == null) return false;
            UserRecord updated = new UserRecord(existing.getUsername(), existing.getCredential(),
                role != null && !role.isEmpty() ? role : existing.getRole(),
                displayName != null && !displayName.trim().isEmpty() ? displayName.trim() : existing.getDisplayName());
            users.put(key, updated);
            usersDirty.set(true);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static String generateSalt() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return CryptoIntegrityService.bytesToHex(bytes);
    }

    /** Salted digest: {@code sha256(salt + ":" + sha256(password))}. */
    public static String hashCredential(String salt, String clientSha256) {
        return CryptoIntegrityService.computeSha256(salt + ":" + (clientSha256 == null ? "" : clientSha256));
    }

    // ------------------------------------------------------------------
    // Rooms (data/rooms.db)
    // ------------------------------------------------------------------

    public List<RoomRecord> getRooms() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(rooms.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public RoomRecord getRoom(String room) {
        if (room == null) return null;
        lock.readLock().lock();
        try {
            return rooms.get(room.toLowerCase());
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean hasRoom(String room) {
        return getRoom(room) != null;
    }

    /** Persists a custom study room. Returns {@code false} when the room already exists. */
    public boolean createRoom(String room, String topic) {
        if (room == null || room.trim().isEmpty()) return false;
        String name = room.trim();
        String key = name.toLowerCase();
        lock.writeLock().lock();
        try {
            if (rooms.containsKey(key)) {
                RoomRecord existing = rooms.get(key);
                if (topic != null && !topic.trim().isEmpty() && !existing.getTopic().equals(topic.trim())) {
                    existing.setTopic(topic.trim());
                    roomsDirty.set(true);
                }
                return false;
            }
            String resolvedTopic = (topic == null || topic.trim().isEmpty()) ? defaultRoomTopic(name) : topic.trim();
            rooms.put(key, new RoomRecord(name, resolvedTopic, ""));
            roomsDirty.set(true);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setRoomTopic(String room, String topic) {
        lock.writeLock().lock();
        try {
            RoomRecord record = rooms.get(room == null ? null : room.toLowerCase());
            if (record != null) {
                record.setTopic(topic == null ? "" : topic.trim());
                roomsDirty.set(true);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Stores (or clears, when {@code payload} is empty) the pinned banner of a room. */
    public void setRoomPinned(String room, String payload) {
        lock.writeLock().lock();
        try {
            RoomRecord record = rooms.get(room == null ? null : room.toLowerCase());
            if (record == null) {
                if (room == null || room.trim().isEmpty()) return;
                record = new RoomRecord(room.trim(), defaultRoomTopic(room.trim()), "");
                rooms.put(room.trim().toLowerCase(), record);
            }
            record.setPinnedPayload(payload == null ? "" : payload);
            roomsDirty.set(true);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String getRoomPinned(String room) {
        RoomRecord record = getRoom(room);
        return record != null ? record.getPinnedPayload() : "";
    }

    public String getRoomTopic(String room) {
        RoomRecord record = getRoom(room);
        return record != null ? record.getTopic() : defaultRoomTopic(room);
    }

    // ------------------------------------------------------------------
    // Vault index (data/vault_index.db)
    // ------------------------------------------------------------------

    public List<VaultEntry> getVaultEntries() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(vault);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<VaultEntry> getVaultEntries(String room) {
        lock.readLock().lock();
        try {
            List<VaultEntry> result = new ArrayList<>();
            for (VaultEntry entry : vault) {
                if (room == null || room.equalsIgnoreCase(entry.getRoom())) {
                    result.add(entry);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void addVaultEntry(VaultEntry entry) {
        if (entry == null) return;
        lock.writeLock().lock();
        try {
            vault.add(entry);
            vaultDirty.set(true);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Resolves the physical filename to stream back for a given room + user-facing name. */
    public VaultEntry findVaultEntry(String room, String originalFilename) {
        if (originalFilename == null) return null;
        for (VaultEntry entry : getVaultEntries(room)) {
            if (entry.getOriginalFilename().equalsIgnoreCase(originalFilename)
                || entry.getStoredFilename().equalsIgnoreCase(originalFilename)) {
                return entry;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Room history (data/history_<room>.log)
    // ------------------------------------------------------------------

    /** Queues an append-only history line for the given room. */
    public void appendHistory(String room, HistoryEntry entry) {
        if (room == null || entry == null) return;
        historyQueue.add(new PendingHistory(room, entry.toLine()));
    }

    /** Convenience overload used by the NIO chat server for plain chat traffic. */
    public void appendMessage(String room, String sender, String role, String encFlag, String payload, String replyRef, String timestamp) {
        appendHistory(room, new HistoryEntry(ProtocolConstants.HIST_MSG, timestamp, sender, role, encFlag, payload, replyRef));
    }

    /**
     * Returns the most recent {@code limit} history entries of a room.
     * Any pending (not yet flushed) lines are written to disk first so that a
     * late joiner always receives the complete conversation.
     */
    public List<HistoryEntry> getRecentHistory(String room, int limit) {
        flushHistory();
        if (room == null) return Collections.emptyList();
        Path file = historyFile(room);
        List<String> lines;
        try {
            lines = readLines(file);
        } catch (IOException e) {
            return Collections.emptyList();
        }
        int from = Math.max(0, lines.size() - Math.max(1, limit));
        List<HistoryEntry> entries = new ArrayList<>();
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().isEmpty()) continue;
            HistoryEntry entry = HistoryEntry.fromLine(line);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    public int getHistoryLineCount(String room) {
        try {
            return readLines(historyFile(room)).size();
        } catch (IOException e) {
            return 0;
        }
    }

    public Path historyFile(String room) {
        return resolve(HISTORY_FILE_PREFIX + sanitizeForFileName(room) + HISTORY_FILE_SUFFIX);
    }

    // ------------------------------------------------------------------
    // Flushing / shutdown
    // ------------------------------------------------------------------

    /** Forces every pending write (history appends + dirty indexes) onto disk. */
    public void flush() {
        flushHistory();
        if (usersDirty.compareAndSet(true, false)) {
            writeUsers();
        }
        if (roomsDirty.compareAndSet(true, false)) {
            writeRooms();
        }
        if (vaultDirty.compareAndSet(true, false)) {
            writeVault();
        }
    }

    private void flushHistory() {
        if (historyQueue.isEmpty()) {
            return;
        }
        Map<String, List<String>> byRoom = new LinkedHashMap<>();
        PendingHistory pending;
        while ((pending = historyQueue.poll()) != null) {
            List<String> lines = byRoom.get(pending.room);
            if (lines == null) {
                lines = new ArrayList<>();
                byRoom.put(pending.room, lines);
            }
            lines.add(pending.line);
        }
        for (Map.Entry<String, List<String>> entry : byRoom.entrySet()) {
            appendLines(historyFile(entry.getKey()), entry.getValue());
        }
    }

    private void writeUsers() {
        List<String> lines = new ArrayList<>();
        for (UserRecord record : getUsers()) {
            lines.add(record.toLine());
        }
        atomicWrite(resolve(USERS_DB_FILE), lines);
    }

    private void writeRooms() {
        List<String> lines = new ArrayList<>();
        for (RoomRecord record : getRooms()) {
            lines.add(record.toLine());
        }
        atomicWrite(resolve(ROOMS_DB_FILE), lines);
    }

    private void writeVault() {
        List<String> lines = new ArrayList<>();
        for (VaultEntry entry : getVaultEntries()) {
            lines.add(entry.toLine());
        }
        atomicWrite(resolve(VAULT_INDEX_FILE), lines);
    }

    /** Flushes everything and stops the background flusher (invoked from the shutdown hook). */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        flush();
        flusher.shutdownNow();
    }

    public boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------
    // Field encoding helpers (| and newline safe, fully reversible)
    // ------------------------------------------------------------------

    public static String encodeField(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
            .replace("|", "\\p")
            .replace("\r", "")
            .replace("\n", "\\n");
    }

    public static String decodeField(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                if (next == 'p') {
                    sb.append('|');
                } else if (next == 'n') {
                    sb.append('\n');
                } else if (next == '\\') {
                    sb.append('\\');
                } else {
                    sb.append(c).append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Joins already encoded fields with the protocol delimiter. */
    public static String join(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(ProtocolConstants.DELIMITER);
            sb.append(fields[i]);
        }
        return sb.toString();
    }

    public static String[] split(String line) {
        return line.split(ProtocolConstants.DELIMITER_REGEX, -1);
    }

    /** Keeps room names filesystem safe on every platform while staying readable. */
    public static String sanitizeForFileName(String name) {
        if (name == null) return "unknown";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.' || c == '#';
            sb.append(allowed ? c : '_');
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? "unknown" : result;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    // ------------------------------------------------------------------
    // Low level java.nio.file helpers
    // ------------------------------------------------------------------

    private Path resolve(String fileName) {
        return dataDir.resolve(fileName);
    }

    private void touchFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
    }

    private List<String> readLines(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private void appendLines(Path file, List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        try {
            if (Files.notExists(dataDir)) {
                Files.createDirectories(dataDir);
            }
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[PERSISTENCE] Failed to append history to " + file + ": " + e.getMessage());
        }
    }

    private void atomicWrite(Path target, List<String> lines) {
        Path temp = target.resolveSibling(target.getFileName() + TMP_SUFFIX);
        try {
            if (Files.notExists(dataDir)) {
                Files.createDirectories(dataDir);
            }
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append('\n');
            }
            Files.write(temp, sb.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[PERSISTENCE] Failed to write " + target + ": " + e.getMessage());
        }
    }
}
