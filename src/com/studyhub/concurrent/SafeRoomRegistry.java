package com.studyhub.concurrent;

import com.studyhub.common.ProtocolConstants;
import com.studyhub.transfer.SharedFileRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [MEMBER 5: Multithreading & URL/HTTP Networking]
 * Thread-safe central state registry managing active sessions, room memberships,
 * user credentials, and the file vault catalog using explicit synchronized blocks.
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

        public UserSession(String sessionToken, String username, String role, String aesKeyBase64) {
            this.sessionToken = sessionToken;
            this.username = username;
            this.role = role;
            this.aesKeyBase64 = aesKeyBase64;
            this.currentRoom = ProtocolConstants.DEFAULT_ROOMS[0];
            this.presenceStatus = ProtocolConstants.STATUS_ONLINE;
            this.lastPulseTimestamp = System.currentTimeMillis();
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
    }

    private final Object stateLock = new Object();

    // Registered accounts (username -> [sha256Password, role])
    private final Map<String, String[]> credentials = new HashMap<>();

    // Active sessions (sessionToken -> UserSession)
    private final Map<String, UserSession> activeSessionsByToken = new HashMap<>();

    // Username -> UserSession
    private final Map<String, UserSession> activeSessionsByUsername = new HashMap<>();

    // Room name -> Set of usernames in the room
    private final Map<String, Set<String>> roomMemberships = new HashMap<>();

    // File repository catalog
    private final List<SharedFileRecord> fileCatalog = new ArrayList<>();

    public SafeRoomRegistry() {
        for (String room : ProtocolConstants.DEFAULT_ROOMS) {
            roomMemberships.put(room, new HashSet<>());
        }
    }

    public void registerAccount(String username, String sha256Password, String role) {
        synchronized (stateLock) {
            credentials.put(username.toLowerCase(), new String[]{sha256Password.toLowerCase(), role});
        }
    }

    public boolean validateCredentials(String username, String sha256Password) {
        synchronized (stateLock) {
            String[] record = credentials.get(username.toLowerCase());
            if (record == null) return false;
            return record[0].equalsIgnoreCase(sha256Password);
        }
    }

    public String getUserRole(String username) {
        synchronized (stateLock) {
            String[] record = credentials.get(username.toLowerCase());
            return record != null ? record[1] : ProtocolConstants.ROLE_STUDENT;
        }
    }

    public UserSession createSession(String username, String aesKeyBase64) {
        synchronized (stateLock) {
            String role = getUserRole(username);
            String token = UUID.randomUUID().toString();
            UserSession session = new UserSession(token, username, role, aesKeyBase64);

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

    public void addFileRecord(SharedFileRecord record) {
        synchronized (stateLock) {
            fileCatalog.add(record);
        }
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

    public int getActiveUserCount() {
        synchronized (stateLock) {
            return activeSessionsByToken.size();
        }
    }
}
