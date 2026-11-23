package com.studyhub.concurrent;

import com.studyhub.common.DataPersistenceManager;
import com.studyhub.common.ProtocolConstants;
import com.studyhub.transfer.SharedFileRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [MEMBER 5: Multithreading & URL/HTTP Networking]
 * Thread-safe central state registry managing active sessions, room memberships,
 * user credentials, and the file vault catalog using explicit synchronized blocks.
 *
 * <p>Every durable slice of state (accounts, custom rooms + topics + pinned banners,
 * and the shared-file vault index) is now backed by {@link DataPersistenceManager},
 * so a server restart restores the workspace exactly as the students left it.</p>
 */
public class SafeRoomRegistry {

    public static class UserSession {
        private final String sessionToken;
        private final String username;
        private final String role;
        private final String aesKeyBase64;
        private volatile String currentRoom;
        private volatile String presenceStatus;
        private volatile long lastPulseTimestamp;
        private volatile String displayName;

        public UserSession(String sessionToken, String username, String role, String aesKeyBase64) {
            this.sessionToken = sessionToken;
            this.username = username;
            this.role = role;
            this.aesKeyBase64 = aesKeyBase64;
            this.currentRoom = ProtocolConstants.DEFAULT_ROOMS[0];
            this.presenceStatus = ProtocolConstants.STATUS_ONLINE;
            this.lastPulseTimestamp = System.currentTimeMillis();
            this.displayName = username;
        }

        public String getSessionToken() { return sessionToken; }
        public String getUsername() { return username; }
        public String getRole() { return role; }
        public String getAesKeyBase64() { return aesKeyBase64; }
        public String getCurrentRoom() { return currentRoom; }
        public void setCurrentRoom(String currentRoom) { this.currentRoom = currentRoom; }
        public String getPresenceStatus() { return presenceStatus; }
        public void setPresenceStatus(String presenceStatus) { this.presenceStatus = presenceStatus; }
        public long getLastPulseTimestamp() { return lastPulseTimestamp; }
        public void updatePulseTimestamp() { this.lastPulseTimestamp = System.currentTimeMillis(); }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
    }

    private final Object stateLock = new Object();

    // Disk persistence engine (flat-file, thread-safe)
    private final DataPersistenceManager persistence;

    // Active sessions (sessionToken -> UserSession)
    private final Map<String, UserSession> activeSessionsByToken = new HashMap<>();

    // Username -> UserSession
    private final Map<String, UserSession> activeSessionsByUsername = new HashMap<>();

    // Room name -> Set of usernames in the room
    private final Map<String, Set<String>> roomMemberships = new LinkedHashMap<>();

    // File repository catalog (hydrated from data/vault_index.db on boot)
    private final List<SharedFileRecord> fileCatalog = new ArrayList<>();

    public SafeRoomRegistry() {
        this(DataPersistenceManager.getInstance());
    }

    public SafeRoomRegistry(DataPersistenceManager persistence) {
        this.persistence = persistence != null ? persistence : DataPersistenceManager.getInstance();
        restoreFromDisk();
    }

    /**
     * Rehydrates rooms, topics, pins and the vault index from the flat files on disk.
     * Invoked once at construction - i.e. on every server initialisation.
     */
    private void restoreFromDisk() {
        for (DataPersistenceManager.RoomRecord record : persistence.getRooms()) {
            roomMemberships.put(record.getName(), new HashSet<String>());
        }
        for (String room : ProtocolConstants.DEFAULT_ROOMS) {
            if (!roomMemberships.containsKey(room)) {
                roomMemberships.put(room, new HashSet<String>());
            }
        }

        List<SharedFileRecord> restored = new ArrayList<>();
        for (DataPersistenceManager.VaultEntry entry : persistence.getVaultEntries()) {
            restored.add(new SharedFileRecord(
                entry.getFileId(),
                entry.getRoom(),
                entry.getOriginalFilename(),
                entry.getStoredFilename(),
                entry.getFileSize(),
                entry.getSha256(),
                entry.getUploader(),
                entry.getTimestamp()
            ));
        }
        synchronized (stateLock) {
            fileCatalog.addAll(restored);
        }
    }

    public DataPersistenceManager getPersistence() {
        return persistence;
    }

    // ------------------------------------------------------------------
    // Accounts (persisted to data/users.db)
    // ------------------------------------------------------------------

    /** Registers a new account. Returns {@code false} when the username is already taken. */
    public boolean registerAccount(String username, String clientSha256, String role, String displayName) {
        return persistence.registerUser(username, clientSha256, role, displayName);
    }

    public boolean registerAccount(String username, String clientSha256, String role) {
        return registerAccount(username, clientSha256, role, username);
    }

    public boolean hasAccount(String username) {
        return persistence.hasUser(username);
    }

    public int getRegisteredUserCount() {
        return persistence.getUserCount();
    }

    public String getDisplayName(String username) {
        return persistence.getDisplayName(username);
    }

    public boolean validateCredentials(String username, String clientSha256) {
        return persistence.credentialsMatch(username, clientSha256);
    }

    public String getUserRole(String username) {
        DataPersistenceManager.UserRecord record = persistence.getUser(username);
        return record != null ? record.getRole() : ProtocolConstants.ROLE_STUDENT;
    }

    public boolean updateProfile(String username, String displayName, String role) {
        return persistence.updateProfile(username, displayName, role);
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    public UserSession createSession(String username, String aesKeyBase64) {
        synchronized (stateLock) {
            String role = getUserRole(username);
            String token = UUID.randomUUID().toString();
            UserSession session = new UserSession(token, username, role, aesKeyBase64);
            session.setDisplayName(getDisplayName(username));

            // Replace existing session for this user if reconnecting
            UserSession old = activeSessionsByUsername.get(username.toLowerCase());
            if (old != null) {
                activeSessionsByToken.remove(old.getSessionToken());
                removeUserFromAllRooms(old.getUsername());
            }

            activeSessionsByToken.put(token, session);
            activeSessionsByUsername.put(username.toLowerCase(), session);
            addUserToRoom(username, session.getCurrentRoom());
            return session;
        }
    }

    public UserSession getSessionByToken(String token) {
        synchronized (stateLock) {
            return activeSessionsByToken.get(token);
        }
    }

    public UserSession getSessionByUsername(String username) {
        synchronized (stateLock) {
            return activeSessionsByUsername.get(username.toLowerCase());
        }
    }

    public void removeSession(String token) {
        synchronized (stateLock) {
            UserSession session = activeSessionsByToken.remove(token);
            if (session != null) {
                activeSessionsByUsername.remove(session.getUsername().toLowerCase());
                removeUserFromAllRooms(session.getUsername());
            }
        }
    }

    public void switchRoom(String username, String newRoom) {
        synchronized (stateLock) {
            removeUserFromAllRooms(username);
            addUserToRoom(username, newRoom);
            UserSession session = activeSessionsByUsername.get(username.toLowerCase());
            if (session != null) {
                session.setCurrentRoom(newRoom);
            }
        }
    }

    public void addUserToRoom(String username, String room) {
        synchronized (stateLock) {
            roomMemberships.computeIfAbsent(room, k -> new HashSet<>()).add(username);
        }
    }

    public void removeUserFromAllRooms(String username) {
        synchronized (stateLock) {
            for (Set<String> members : roomMemberships.values()) {
                members.remove(username);
            }
        }
    }

    public List<String> getRoomMembers(String room) {
        synchronized (stateLock) {
            Set<String> set = roomMemberships.get(room);
            if (set == null) return Collections.emptyList();
            return new ArrayList<>(set);
        }
    }

    public Map<String, String> getPresenceMapForRoom(String room) {
        synchronized (stateLock) {
            Map<String, String> presence = new HashMap<>();
            Set<String> members = roomMemberships.get(room);
            if (members != null) {
                for (String user : members) {
                    UserSession session = activeSessionsByUsername.get(user.toLowerCase());
                    if (session != null) {
                        presence.put(user, session.getPresenceStatus());
                    } else {
                        presence.put(user, ProtocolConstants.STATUS_ONLINE);
                    }
                }
            }
            return presence;
        }
    }

    public String getRoleForUser(String username) {
        synchronized (stateLock) {
            UserSession session = activeSessionsByUsername.get(username == null ? null : username.toLowerCase());
            if (session != null) {
                return session.getRole();
            }
        }
        return getUserRole(username);
    }

    // ------------------------------------------------------------------
    // Rooms & topics (persisted to data/rooms.db)
    // ------------------------------------------------------------------

    public List<String> getRoomList() {
        List<String> rooms = new ArrayList<>();
        for (String room : ProtocolConstants.DEFAULT_ROOMS) {
            if (!rooms.contains(room)) {
                rooms.add(room);
            }
        }
        for (DataPersistenceManager.RoomRecord record : persistence.getRooms()) {
            if (!rooms.contains(record.getName())) {
                rooms.add(record.getName());
            }
        }
        return rooms;
    }

    public Map<String, String> getRoomTopics() {
        Map<String, String> topics = new LinkedHashMap<>();
        for (String room : getRoomList()) {
            topics.put(room, persistence.getRoomTopic(room));
        }
        return topics;
    }

    /** Persists a custom study room. Returns {@code false} when the room already exists. */
    public boolean createRoom(String room, String topic) {
        if (room == null || room.trim().isEmpty()) return false;
        boolean created = persistence.createRoom(room.trim(), topic);
        synchronized (stateLock) {
            if (!roomMemberships.containsKey(room.trim())) {
                roomMemberships.put(room.trim(), new HashSet<String>());
            }
        }
        return created;
    }

    public void setRoomTopic(String room, String topic) {
        persistence.setRoomTopic(room, topic);
    }

    public String getRoomTopic(String room) {
        return persistence.getRoomTopic(room);
    }

    /** Stores (or clears when {@code payload} is empty) the pinned banner payload of a room. */
    public void setRoomPinned(String room, String payload) {
        persistence.setRoomPinned(room, payload);
    }

    public String getRoomPinned(String room) {
        return persistence.getRoomPinned(room);
    }

    // ------------------------------------------------------------------
    // File vault (persisted to data/vault_index.db)
    // ------------------------------------------------------------------

    public void addFileRecord(SharedFileRecord record) {
        synchronized (stateLock) {
            fileCatalog.add(record);
        }
        persistence.addVaultEntry(new DataPersistenceManager.VaultEntry(
            record.getFileId(),
            record.getRoom(),
            record.getUploader(),
            record.getFilename(),
            record.getStoredFilename(),
            record.getFileSize(),
            record.getSha256(),
            record.getTimestamp()
        ));
    }

    public List<SharedFileRecord> getFilesForRoom(String room) {
        synchronized (stateLock) {
            List<SharedFileRecord> result = new ArrayList<>();
            for (SharedFileRecord rec : fileCatalog) {
                if (rec.getRoom().equalsIgnoreCase(room)) {
                    result.add(rec);
                }
            }
            return result;
        }
    }

    public List<SharedFileRecord> getAllFilesSnapshot() {
        synchronized (stateLock) {
            return new ArrayList<>(fileCatalog);
        }
    }

    /** Persist-backed lookup used by the download path to map display name -> stored name. */
    public SharedFileRecord findFile(String room, String filename) {
        synchronized (stateLock) {
            for (SharedFileRecord record : fileCatalog) {
                if (record.getRoom().equalsIgnoreCase(room)
                    && (record.getFilename().equalsIgnoreCase(filename) || record.getStoredFilename().equalsIgnoreCase(filename))) {
                    return record;
                }
            }
            return null;
        }
    }

    public int getActiveUserCount() {
        synchronized (stateLock) {
            Set<String> online = new HashSet<>();
            for (Set<String> members : roomMemberships.values()) online.addAll(members);
            return online.size();
        }
    }
}
