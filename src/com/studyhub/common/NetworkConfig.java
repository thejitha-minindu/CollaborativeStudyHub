package com.studyhub.common;

/**
 * Global network configuration parameters, port allocations, buffer sizes,
 * and socket timeout defaults for Collaborative Study Hub.
 */
public final class NetworkConfig {

    private NetworkConfig() {}

    // Port Allocations
    public static final int TLS_AUTH_PORT = 9443;
    public static final int NIO_CHAT_PORT = 9000;
    public static final int FILE_TRANSFER_PORT = 9005;
    public static final int UDP_DISCOVERY_PORT = 9876;
    public static final int UDP_PULSE_PORT = 9877;

    // Host & LAN Defaults
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final String BROADCAST_IP = "255.255.255.255";
    public static final String LOCALHOST_IP = "127.0.0.1";

    // Buffer & Chunk Sizes
    public static final int NIO_BUFFER_CAPACITY = 8192;
    public static final int FILE_CHUNK_SIZE = 16384; // 16 KB chunk for FileChannel

    // Intervals & Timeouts (milliseconds)
    public static final int UDP_BEACON_INTERVAL_MS = 3000;
    public static final int UDP_DISCOVERY_TIMEOUT_MS = 4000;
    public static final int UDP_PULSE_INTERVAL_MS = 5000;
    public static final int HTTP_CONNECT_TIMEOUT_MS = 3000;
    public static final int HTTP_READ_TIMEOUT_MS = 3000;
    public static final int TCP_SOCKET_TIMEOUT_MS = 10000;

    // Security & Keystore Constants
    public static final String KEYSTORE_PATH = "certs/studyhub_keystore.p12";
    public static final String KEYSTORE_PASSWORD = "studyhub123";
    public static final String KEYSTORE_TYPE = "PKCS12";
    public static final String KEY_ALIAS = "studyhub";
    public static final String TLS_PROTOCOL = "TLSv1.3";

    // Shared Storage Directory
    public static final String SHARED_STORAGE_DIR = "shared_storage";
    public static final String DOWNLOADS_DIR = "downloads";

    // Disk Persistence (flat-file storage engine, see com.studyhub.common.DataPersistenceManager)
    public static final String DATA_DIR = "data";
    public static final int HISTORY_REPLAY_LIMIT = 50;   // messages replayed to a late joiner
    public static final int RECONNECT_INTERVAL_MS = 3000; // client NIO SocketChannel retry backoff
}
