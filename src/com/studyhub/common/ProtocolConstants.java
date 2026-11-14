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

    // NIO Chat Commands (Port 9000)
    public static final String CMD_HELLO = "HELLO";
    public static final String CMD_JOIN = "JOIN";
    public static final String CMD_MSG = "MSG";
    public static final String CMD_DM = "DM";
    public static final String CMD_LEAVE = "LEAVE";

    // NIO Server Broadcast Types
    public static final String EVT_CHAT = "CHAT";
    public static final String EVT_USER_LIST = "USER_LIST";
    public static final String EVT_LINK_CARD = "LINK_CARD";
    public static final String EVT_FILE_ADDED = "FILE_ADDED";
    public static final String EVT_PRESENCE_UPDATE = "PRESENCE_UPDATE";
    public static final String EVT_SYSTEM = "SYSTEM";

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

    // Payload Encryption Flags
    public static final String ENC_PLAIN = "PLAIN";
    public static final String ENC_AES = "AES";
}
