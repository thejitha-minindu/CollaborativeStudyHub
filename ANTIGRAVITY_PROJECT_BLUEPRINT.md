# ANTIGRAVITY PROJECT BLUEPRINT: Collaborative Study Hub & File Sharing Platform

## 1. Agent Mission & System Constraints
You are tasked with designing, scaffolding, implementing, and verifying a complete, production-grade Java networking application: **Collaborative Study Hub & File Sharing Platform**.

### Strict Technical Constraints
1. **Zero External Dependencies (100% Standard Java SE 17+):**
   - Use ONLY standard JDK libraries: `java.net.*`, `java.nio.*`, `javax.net.ssl.*`, `java.util.concurrent.*`, `java.security.*`, `java.io.*`, `java.time.*`, and `javax.swing.*` (for the desktop GUI).
   - Do NOT use Maven, Gradle, JavaFX, Spring, or third-party `.jar` files. The project must compile cleanly with standard `javac` and run with `java` out of the box when extracted from a `.zip` archive.
2. **Strict 5-Module Package Isolation:**
   - The codebase must be cleanly separated into five domain packages corresponding to 5 university group members, plus `common`, `server`, and `ui` packages, so each member can independently demonstrate their code during a 2-minute evaluation.
3. **Interactive "Network Inspector" UI:**
   - The Swing client GUI must not only provide a clean, modern chat/file-sharing experience, but also include a dedicated **Network Diagnostics & Inspector Panel** displaying live protocol metrics (NIO `ByteBuffer` states, TLS certificate details, SHA-256 checksums, UDP beacon sequence numbers, and `ExecutorService` thread pool activity) to make every underlying networking concept visually demonstrable.
4. **Automated Keystore & Storage Bootstrap:**
   - If `certs/studyhub_keystore.p12` does not exist at startup, the server bootstrapper must automatically invoke `keytool` (via `ProcessBuilder`) or programmatic certificate generation so TLS works immediately on any machine without manual setup.

---

## 2. Exact Project Directory & File Structure

Create the following directory tree and files:

```text
CollaborativeStudyHub/
├── build.bat
├── build.sh
├── run_server.bat
├── run_server.sh
├── run_client.bat
├── run_client.sh
├── README.md
├── certs/                          # Auto-populated with studyhub_keystore.p12
├── shared_storage/                 # Server repository for uploaded study files
└── src/
    └── com/
        └── studyhub/
            ├── common/
            │   ├── NetworkConfig.java          # Ports, host defaults, buffer sizes, timeouts
            │   ├── ProtocolConstants.java      # Wire protocol prefixes and delimiters
            │   └── PacketLogEvent.java         # Telemetry event model for UI Inspector
            ├── nio/                            # [MEMBER 1: Java NIO Non-Blocking Multiplexing]
            │   ├── NioChatServer.java          # Single-thread Selector event loop (OP_ACCEPT, OP_READ, OP_WRITE)
            │   ├── ClientChannelContext.java   # Per-client read/write ByteBuffers, username, room
            │   └── MessageFramer.java          # 4-byte length-prefix framing using flip(), compact(), clear()
            ├── transfer/                       # [MEMBER 2: TCP Sockets & NIO FileChannel]
            │   ├── FileTransferServer.java     # TCP ServerSocket + FileChannel disk writer/reader
            │   ├── FileTransferClient.java     # TCP Socket uploader/downloader with timeout handling
            │   └── SharedFileRecord.java       # Metadata model (fileId, room, filename, size, sha256, uploader)
            ├── security/                       # [MEMBER 3: Secure Network Programming]
            │   ├── KeystoreBootstrap.java      # Auto-generates PKCS12 keystore via keytool if absent
            │   ├── TlsAuthServer.java          # SSLServerSocket authentication & token issuer
            │   ├── TlsAuthClient.java          # SSLSocket client with certificate inspection & TrustManager
            │   └── CryptoIntegrityService.java # SHA-256 MessageDigest hashing & AES-128 payload encryption
            ├── udp/                            # [MEMBER 4: UDP Connectionless Networking]
            │   ├── LanDiscoveryBroadcaster.java# Periodic UDP broadcast beacons (Port 9876)
            │   ├── LanDiscoveryListener.java   # UDP client listener with SocketTimeoutException handling
            │   └── PresencePulseService.java   # UDP typing/online heartbeat receiver with sequence filtering (Port 9877)
            ├── concurrent/                     # [MEMBER 5: Multithreading & URL/HTTP Networking]
            │   ├── ResourceConcurrentEngine.java # FixedThreadPool ExecutorService coordinator
            │   ├── LinkPreviewCallable.java    # Callable<LinkPreview> using URI, URL, InetAddress, HttpURLConnection
            │   ├── LinkPreview.java            # Model for fetched URL title, status code, host IP, latency
            │   ├── FileSearchCallable.java     # Callable<List<SharedFileRecord>> async catalog search
            │   └── SafeRoomRegistry.java       # Thread-safe room/file state using synchronized blocks
            ├── server/
            │   └── StudyHubServerMain.java     # Unified entry point launching all 4 network services + CLI monitor
            └── ui/
                ├── StudyHubClientMain.java     # Client entry point
                ├── LoginDiscoveryDialog.java   # UDP auto-discovery server picker + TLS login dialog
                ├── MainHubFrame.java           # Main workspace (Rooms, Chat, Link Cards, File Vault, Presence)
                └── NetworkInspectorPanel.java  # Live protocol telemetry tabs for all 5 networking modules
```

---

## 3. Detailed Port & Wire Protocol Specification

### Port Allocations (`NetworkConfig.java`)
- `TLS_AUTH_PORT = 9443` (TCP over TLSv1.2/TLSv1.3)
- `NIO_CHAT_PORT = 9000` (TCP Non-Blocking `ServerSocketChannel`)
- `FILE_TRANSFER_PORT = 9001` (TCP `ServerSocket` + `FileChannel`)
- `UDP_DISCOVERY_PORT = 9876` (UDP Server $\rightarrow$ Client LAN Broadcast)
- `UDP_PULSE_PORT = 9877` (UDP Client $\rightarrow$ Server Presence/Typing Datagrams)

### Protocol Specifications

#### 3.1 UDP LAN Discovery & Presence (`com.studyhub.udp`)
- **Discovery Beacon (`Port 9876`):**
  - Broadcast every `3000 ms` to `255.255.255.255` AND `127.0.0.1` (ensuring localhost single-PC demos work even if broadcast is filtered).
  - Payload: `BEACON|<seqNum>|<hubName>|<tlsPort>|<nioPort>|<filePort>|<pulsePort>|<activeUsers>`
  - Client `LanDiscoveryListener` binds a `DatagramSocket`, sets `setSoTimeout(4000)`, collects active hubs, and catches `SocketTimeoutException` gracefully without crashing.
- **Presence & Typing Pulse (`Port 9877`):**
  - Client sends fire-and-forget datagrams on keystroke or every `5000 ms`:
    `PULSE|<seqNum>|<username>|<room>|<ONLINE|TYPING|IDLE>`
  - Server `PresencePulseService` maintains `Map<String, Long> lastSequenceByUser`. If `incomingSeq <= lastSeq`, the packet is logged as **Out-of-Order / Stale Dropped** in telemetry and discarded. Valid updates trigger an NIO chat room status broadcast.

#### 3.2 TLS Authentication & Encryption (`com.studyhub.security`)
- **Keystore (`KeystoreBootstrap.java`):**
  - Checks for `certs/studyhub_keystore.p12`. If missing, executes `keytool -genkeypair -alias studyhub -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore certs/studyhub_keystore.p12 -validity 365 -storepass studyhub123 -dname "CN=StudyHubServer, OU=NetworkProgramming, O=University, L=Colombo, C=LK"`.
- **TLS Handshake (`TlsAuthServer` & `TlsAuthClient` on `Port 9443`):**
  - Pre-seeded demo accounts (and dynamic registration):
    - `student1` / `pass123` (Role: `STUDENT`)
    - `student2` / `pass123` (Role: `STUDENT`)
    - `student3` / `pass123` (Role: `STUDENT`)
    - `admin` / `admin123` (Role: `MODERATOR`)
  - Client sends SHA-256 hashed password over `SSLSocket`:
    `AUTH|<username>|<passwordSha256>`
  - Server validates credentials, generates a random UUID `<sessionToken>` and a 128-bit AES `<sessionKeyBase64>`, registers the session in `SafeRoomRegistry`, and replies:
    `AUTH_OK|<sessionToken>|<role>|<sessionKeyBase64>` or `AUTH_FAIL|<reason>`
  - `TlsAuthClient` extracts the peer `X509Certificate` (`subjectDN`, `issuerDN`, `validTo`, `cipherSuite`, `protocol`) and exposes it to `NetworkInspectorPanel`.

#### 3.3 Java NIO Non-Blocking Chat & Control (`com.studyhub.nio`)
- **Single-Thread Event Loop (`NioChatServer` on `Port 9000`):**
  - Opens `Selector`, opens `ServerSocketChannel`, sets `configureBlocking(false)`, binds to `9000`, registers `SelectionKey.OP_ACCEPT`.
  - Runs on ONE dedicated thread (`"NIO-Selector-EventLoop"`).
  - Never performs blocking disk or HTTP I/O on this thread.
- **ByteBuffer Framing (`MessageFramer.java`):**
  - Every frame on the wire is `[4-byte big-endian int length][UTF-8 payload bytes]`.
  - **Outbound:** Allocates `ByteBuffer.allocate(4 + payload.length)`, calls `putInt(payload.length)`, `put(payload)`, and **`buffer.flip()`** before queuing to `SocketChannel.write()`.
  - **Inbound:** Reads from `SocketChannel` into a client-specific `ByteBuffer readBuffer = ByteBuffer.allocate(8192)`. Calls **`readBuffer.flip()`**, loops while `readBuffer.remaining() >= 4`, checks if full frame is available (`readBuffer.remaining() >= frameLen`), extracts complete frames, and finally calls **`readBuffer.compact()`** so any partial trailing frame is preserved at index `0` for the next `OP_READ` event.
  - Tracks metrics (`totalFramesRead`, `compactInvocations`, `lastPosition`, `lastLimit`, `lastCapacity`) for live UI inspection.
- **NIO Commands:**
  - `HELLO|<sessionToken>|<username>` $\rightarrow$ Binds `SocketChannel` to authenticated user, joins `#general`.
  - `JOIN|<roomName>` $\rightarrow$ Moves user to `#general`, `#network-programming`, `#algorithms`, or `#exam-prep` and broadcasts updated `USER_LIST`.
  - `MSG|<roomName>|<encFlag:PLAIN|AES>|<content>` $\rightarrow$ Broadcasts to all channels in `<roomName>`. If `<content>` contains an `http://` or `https://` URL, hands off URL extraction to `ResourceConcurrentEngine` asynchronously!
  - `DM|<targetUser>|<encFlag>|<content>` $\rightarrow$ Routes directly to target user's `SocketChannel`.

#### 3.4 Reliable File Transfer with `FileChannel` & SHA-256 (`com.studyhub.transfer`)
- **Server (`FileTransferServer` on `Port 9001`):**
  - Uses `java.net.ServerSocket` accepting TCP connections and submitting each transfer session to a worker pool.
  - **Upload Flow:**
    1. Reads UTF-8 header line: `UPLOAD|<sessionToken>|<room>|<filename>|<fileSize>|<expectedSha256>|<simulateTamper:true|false>`
    2. Opens a temporary `FileChannel` (`StandardOpenOption.CREATE`, `WRITE`, `TRUNCATE_EXISTING`).
    3. Loops reading exact `fileSize` bytes from `InputStream` into a `ByteBuffer`, updating `MessageDigest("SHA-256")`, flipping the buffer (`flip()`), writing to `FileChannel`, and clearing (`clear()`).
    4. If `simulateTamper == true` (triggered by a demo checkbox in the UI), flips 1 byte in memory before finalizing the digest to intentionally demonstrate tamper detection!
    5. Compares computed SHA-256 against `expectedSha256`:
       - **Match:** Moves file into `shared_storage/<room>/<filename>`, records `SharedFileRecord` in `SafeRoomRegistry`, sends `UPLOAD_OK|<actualSha256>`, and triggers `NioChatServer` to broadcast `FILE_ADDED|<room>|<filename>|<fileSize>|<uploader>|<actualSha256>` to the room.
       - **Mismatch:** Deletes temp file and responds `UPLOAD_TAMPERED|<expectedSha256>|<actualSha256>`.
  - **Download Flow:**
    1. Client sends `DOWNLOAD|<sessionToken>|<room>|<filename>`
    2. Server verifies file exists, opens `FileChannel` (`READ`), sends `DOWNLOAD_OK|<fileSize>|<sha256>`, and streams chunks from `FileChannel` $\rightarrow$ `ByteBuffer` $\rightarrow$ `OutputStream`.
    3. Client verifies SHA-256 upon completion before saving to local `downloads/`.

#### 3.5 Multithreading & URL/HTTP Preview Engine (`com.studyhub.concurrent`)
- **Thread Pool (`ResourceConcurrentEngine.java`):**
  - Uses `ExecutorService executor = Executors.newFixedThreadPool(5)`.
  - Exposes pool metrics (`activeThreads`, `completedTasks`, `queueSize`) to `NetworkInspectorPanel`.
- **Link Preview Task (`LinkPreviewCallable implements Callable<LinkPreview>`):**
  - Triggered whenever a chat message contains a URL.
  - Step 1: Validates syntax via `new URI(rawUrl)` (catches `URISyntaxException`).
  - Step 2: Converts to `uri.toURL()` (catches `MalformedURLException`).
  - Step 3: Resolves host IP via `InetAddress.getByName(url.getHost())` (catches `UnknownHostException`).
  - Step 4: Opens `HttpURLConnection` with `setConnectTimeout(3000)`, `setReadTimeout(3000)`, `setRequestProperty("User-Agent", "StudyHub-LinkPreview/1.0")`.
  - Step 5: Reads HTTP status code (`getResponseCode()`) and parses `<title>...</title>` from the HTML stream.
  - Step 6: If offline or timed out, returns a graceful fallback `LinkPreview` containing parsed URI scheme, host, path, and status `"OFFLINE / TIMEOUT"`.
  - Step 7: When `Future<LinkPreview>` resolves, `NioChatServer` broadcasts `LINK_CARD|<room>|<url>|<hostIp>|<statusCode>|<title>` to the room.
- **Synchronized Shared State (`SafeRoomRegistry.java`):**
  - Protects shared room memberships, active user sessions, and file catalogs using explicit `synchronized` methods/blocks to prevent race conditions during concurrent uploads and room switches.

---

## 4. Client Desktop GUI Specification (`com.studyhub.ui`)

Build a crisp, modern, clean **Java Swing** interface (`MainHubFrame` & `LoginDiscoveryDialog`) with a cohesive dark-slate/indigo academic theme:

1. **Login & UDP Discovery Dialog (`LoginDiscoveryDialog.java`):**
   - Top panel: **"LAN Study Hubs Discovered (UDP Port 9876)"** table auto-populated in real time by `LanDiscoveryListener`, plus a manual host input fallback (`127.0.0.1`).
   - Credentials form pre-filled with quick-select buttons (`Student 1`, `Student 2`, `Moderator`) for rapid 2-minute live evaluation switching.
   - Displays TLS connection status and certificate thumbprint upon login.
2. **Main Hub Workspace (`MainHubFrame.java`):**
   - **Left Sidebar:** Study Rooms list (`#general`, `#network-programming`, `#algorithms`, `#exam-prep`) + Online Members list with live UDP presence badges (`[ONLINE]`, `[TYPING...]`).
   - **Center Panel (Chat & Rich Cards):**
     - Scrollable message feed rendering timestamps, sender badges, AES-encrypted lock icons (`[AES-128]`), clickable **Link Preview Cards** (showing resolved IP, HTTP status badge, and page title), and **File Share Cards** with one-click **Download** buttons.
     - Bottom input bar with:
       - Text field (triggers UDP `TYPING` pulses on keypress).
       - **"Encrypt (AES)"** toggle checkbox.
       - **"Share File"** button + **"Simulate Tamper (Demo)"** checkbox (so Member 3 can demonstrate SHA-256 tamper rejection in 1 click).
   - **Right / Bottom Collapsible Panel (`NetworkInspectorPanel.java`):**
     - Tabbed live telemetry viewer tailored to the 5 group members:
       1. **NIO Selector & Buffer:** Shows `ByteBuffer` `position`, `limit`, `capacity`, `flip()`/`compact()` counters, and framed hex/byte stats.
       2. **TCP FileChannel:** Shows chunk transfer logs, throughput (KB/s), and `FileChannel` read/write operations.
       3. **TLS & Security:** Shows X.509 certificate fields, TLS cipher suite, and SHA-256 hash verification logs.
       4. **UDP Beacons & Pulse:** Shows incoming UDP broadcast packets, `SocketTimeoutException` status, and `PULSE` sequence numbers (highlighting dropped out-of-order packets).
       5. **Threads & HTTP:** Shows `ExecutorService` thread pool stats, `Callable`/`Future` task durations, and `HttpURLConnection` DNS/HTTP lookup results.

---

## 5. Build, Run Scripts & Verification Steps

### Scripts to Generate
- `build.bat` & `build.sh`: Compiles all `.java` sources under `src/` into `out/` (`javac -d out ...`).
- `run_server.bat` & `run_server.sh`: Launches `com.studyhub.server.StudyHubServerMain`.
- `run_client.bat` & `run_client.sh`: Launches `com.studyhub.ui.StudyHubClientMain`.

### Agent Verification Protocol
After generating all files, execute the following verification steps before finishing:
1. Run `build.sh` (or `javac`) and verify **zero compilation errors or warnings**.
2. Start `StudyHubServerMain` in the background and confirm:
   - `certs/studyhub_keystore.p12` is auto-created.
   - Ports `9443`, `9000`, `9001`, `9876`, and `9877` bind cleanly.
3. Write and run an automated headless integration smoke test (`VerifyIntegrationTest`) that:
   - Discovers the server via UDP broadcast on `9876`.
   - Authenticates via TLS on `9443` and receives a valid token + AES key.
   - Connects via NIO `SocketChannel` on `9000`, joins `#network-programming`, and exchanges framed messages.
   - Uploads a test PDF via TCP + `FileChannel` on `9001` (verifying both `UPLOAD_OK` and `UPLOAD_TAMPERED` when tampered).
   - Submits a URL message (`https://example.com`) and verifies `LinkPreviewCallable` resolves via `ExecutorService` + `HttpURLConnection`.
   - Cleanly shuts down the test server after verification.