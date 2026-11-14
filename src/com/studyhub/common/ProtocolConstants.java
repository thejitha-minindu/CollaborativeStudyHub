package com.studyhub.common;

/**
 * Protocol delimiters, message prefixes, room definitions, and user roles
 * across the Collaborative Study Hub ecosystem.
 */
public final class ProtocolConstants {

    private ProtocolConstants() {}

    public static final String DELIMITER = "|";
    public static final String DELIMITER_REGEX = "\\|";

    // Standard Study Rooms
    public static final String[] DEFAULT_ROOMS = {
        "#general",
        "#network-programming",
        "#algorithms",
        "#exam-prep"
    };

    // User Roles
    public static final String ROLE_STUDENT = "STUDENT";
    public static final String ROLE_MODERATOR = "MODERATOR";

    // TLS Auth Protocol Commands (Port 9443)
    public static final String CMD_AUTH = "AUTH";
    public static final String RESP_AUTH_OK = "AUTH_OK";
    public static final String RESP_AUTH_FAIL = "AUTH_FAIL";

    // Self-service student registration (Port 9443, same TLS session)
    public static final String CMD_REGISTER = "REGISTER";
    public static final String RESP_REG_OK = "REG_OK";
    public static final String RESP_REG_FAIL = "REG_FAIL";

    // NIO Chat Commands (Port 9000)
    public static final String CMD_HELLO = "HELLO";
    public static final String CMD_JOIN = "JOIN";
    public static final String CMD_MSG = "MSG";
    public static final String CMD_DM = "DM";
    public static final String CMD_LEAVE = "LEAVE";

    // NIO Commands added for the persistent / collaborative upgrades
    public static final String CMD_HISTORY = "HISTORY";            // request replay of a room transcript
    public static final String CMD_CREATE_ROOM = "CREATE_ROOM";    // CREATE_ROOM|<room>|<topic>
    public static final String CMD_PIN = "PIN";                    // PIN|<room>|<payload> (empty payload = unpin)
    public static final String CMD_SET_TOPIC = "SET_TOPIC";        // SET_TOPIC|<room>|<topic>

    // NIO Server Broadcast Types
    public static final String EVT_CHAT = "CHAT";
    public static final String EVT_USER_LIST = "USER_LIST";
    public static final String EVT_LINK_CARD = "LINK_CARD";
    public static final String EVT_FILE_ADDED = "FILE_ADDED";
    public static final String EVT_PRESENCE_UPDATE = "PRESENCE_UPDATE";
    public static final String EVT_SYSTEM = "SYSTEM";

    // NIO Server Broadcast Types added for the persistent / collaborative upgrades
    public static final String EVT_HISTORY_BATCH = "HISTORY_BATCH"; // HISTORY_BATCH|<room>|<count>|<entry>|...
    public static final String EVT_ROOM_LIST = "ROOM_LIST";         // ROOM_LIST|<room>:<topic>|...
    public static final String EVT_PINNED = "PINNED";               // PINNED|<room>|<payload>
    public static final String EVT_VAULT_LIST = "VAULT_LIST";       // VAULT_LIST|<room>|<fileId>|<orig>|<stored>|<size>|<sha>|<uploader>|<ts>

    // File Transfer Protocol Commands (Port 9001)
    public static final String CMD_UPLOAD = "UPLOAD";
    public static final String RESP_UPLOAD_OK = "UPLOAD_OK";
    public static final String RESP_UPLOAD_TAMPERED = "UPLOAD_TAMPERED";
    public static final String RESP_UPLOAD_FAIL = "UPLOAD_FAIL";

    public static final String CMD_DOWNLOAD = "DOWNLOAD";
    public static final String RESP_DOWNLOAD_OK = "DOWNLOAD_OK";
    public static final String RESP_DOWNLOAD_FAIL = "DOWNLOAD_FAIL";

    // UDP Protocols
    public static final String UDP_BEACON_HEADER = "BEACON"; // Port 9876
    public static final String UDP_PULSE_HEADER = "PULSE";   // Port 9877

    // Presence Statuses
    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_TYPING = "TYPING";
    public static final String STATUS_IDLE = "IDLE";

    // Live study statuses selectable from the user profile bar (broadcast via UDP PULSE 9877)
    public static final String STATUS_AVAILABLE = "AVAILABLE";   // "Available"
    public static final String STATUS_FOCUS = "FOCUS";           // "Focusing / Do Not Disturb"
    public static final String STATUS_LAB = "IN_LAB";            // "In Lab"

    public static final String[] SELECTABLE_STATUSES = {STATUS_AVAILABLE, STATUS_FOCUS, STATUS_LAB};

    public static String describeStatus(String status) {
        if (status == null) return "Online";
        if (STATUS_TYPING.equalsIgnoreCase(status)) return "typing...";
        if (STATUS_FOCUS.equalsIgnoreCase(status)) return "Focusing / DND";
        if (STATUS_LAB.equalsIgnoreCase(status)) return "In Lab";
        if (STATUS_AVAILABLE.equalsIgnoreCase(status)) return "Available";
        if (STATUS_IDLE.equalsIgnoreCase(status)) return "Idle";
        return "Online";
    }

    // Payload Encryption Flags
    public static final String ENC_PLAIN = "PLAIN";
    public static final String ENC_AES = "AES";

    // Append-only history record kinds (data/history_<room>.log)
    public static final String HIST_MSG = "MSG";
    public static final String HIST_LINK = "LINK";
    public static final String HIST_FILE = "FILE";
    public static final String HIST_SYSTEM = "SYSTEM";
}
