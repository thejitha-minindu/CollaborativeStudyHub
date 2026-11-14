# Collaborative Study Hub & File Sharing Platform

A production-grade, zero-dependency Java networking platform built strictly with standard **Java SE 17+ (JDK 21)**. Designed with modular package isolation to demonstrate fundamental and advanced computer networking concepts across 5 specialized domains.

---

## 🏛️ System Architecture & 5-Module Isolation

```
src/com/studyhub/
├── common/                  # NetworkConfig, ProtocolConstants, PacketLogEvent
├── nio/         [MEMBER 1]  # Java NIO Non-Blocking Multiplexing (Port 9000)
├── transfer/    [MEMBER 2]  # TCP Sockets & NIO FileChannel Vault (Port 9001)
├── security/    [MEMBER 3]  # TLSv1.3 Authentication & AES/SHA-256 (Port 9443)
├── udp/         [MEMBER 4]  # UDP Connectionless Beacons & Pulse (Port 9876 & 9877)
├── concurrent/  [MEMBER 5]  # FixedThreadPool & HTTP Link Preview Engine
├── server/                  # StudyHubServerMain (Unified Bootstrapper)
└── ui/                      # Swing Client GUI & Real-Time Network Inspector
```

---

## 👥 5-Member 2-Minute Evaluation Talking Points

| Member | Package | Key Concepts Demonstrated | Demonstration Highlights |
|---|---|---|---|
| **Member 1** | `com.studyhub.nio` | Non-blocking I/O, `Selector`, `ServerSocketChannel`, `SocketChannel`, `ByteBuffer` state transitions (`flip()`, `compact()`, `clear()`), 4-byte big-endian length prefix framing. | Show the single-threaded event loop (`"NIO-Selector-EventLoop"`), show `position`/`limit`/`capacity` tracking in the Network Inspector tab, and explain how `compact()` preserves trailing frame fragments. |
| **Member 2** | `com.studyhub.transfer` | TCP `ServerSocket`, streaming I/O, `FileChannel` block transfers, chunked memory buffers, SHA-256 digest computation. | Click **Share File** to upload a document to the server vault. Explain `StandardOpenOption` operations, zero-corruption chunking, and throughput metrics in the FileChannel Inspector tab. |
| **Member 3** | `com.studyhub.security` | `SSLServerSocket`, `SSLSocket`, TLSv1.3 handshake, PKCS12 keystore bootstrapping, X.509 certificate inspection, AES-128-CBC encryption, SHA-256 tamper rejection. | Click **Simulate Tamper (Demo)** during file upload to demonstrate instant SHA-256 mismatch rejection (`UPLOAD_TAMPERED`). Show X.509 Subject DN, cipher suites, and AES encryption badge in chat. |
| **Member 4** | `com.studyhub.udp` | Connectionless UDP datagrams, `DatagramSocket`, `DatagramPacket`, LAN broadcast (`255.255.255.255`), `SocketTimeoutException` handling, sequence number tracking. | Show server auto-discovered in the login dialog without typing an IP. Show real-time `[TYPING...]` status on keypress, and demonstrate out-of-order stale packet dropping in telemetry. |
| **Member 5** | `com.studyhub.concurrent` | `ExecutorService`, `ThreadPoolExecutor`, `Callable<T>`, `Future<T>`, `URI`, `URL`, `InetAddress` DNS resolution, `HttpURLConnection`. | Post an HTTP/HTTPS link in chat (e.g., `https://example.com`). Observe async background resolution without stalling the NIO thread, generating a rich preview card with IP address, HTTP status, and HTML title. |

---

## 🔌 Port Allocations & Wire Protocols

| Port | Transport | Protocol | Purpose | Wire Format Sample |
|---|---|---|---|---|
| **9443** | TCP (TLSv1.3) | TLS Authentication | Mutual cipher negotiation & session token issuance | `AUTH\|<username>\|<sha256Password>` $\rightarrow$ `AUTH_OK\|<token>\|<role>\|<aesKey>` |
| **9000** | TCP (NIO) | Length-Prefixed TCP | Non-blocking chat & room multiplexing | `[4-byte len]MSG\|#general\|PLAIN\|Hello World!` |
| **9001** | TCP (Stream) | TCP File Transfer | `FileChannel` disk storage & SHA-256 validation | `UPLOAD\|<token>\|<room>\|<filename>\|<size>\|<sha256>\|<tamper>` |
| **9876** | UDP (Broadcast) | Datagram Beacon | Periodic LAN server discovery (3000ms) | `BEACON\|<seq>\|<name>\|9443\|9000\|9001\|9877\|<users>` |
| **9877** | UDP (Datagram) | Presence Pulse | Fire-and-forget keystroke typing/online heartbeat | `PULSE\|<seq>\|<username>\|<room>\|<ONLINE\|TYPING>` |

---

## ⚡ Quick Start & Verification

### 1. Build the Codebase
```bash
# Windows
build.bat

# Linux / macOS
chmod +x build.sh && ./build.sh
```

### 2. Run the Verification Test Suite
```bash
# Runs full headless integration test verifying all 5 modules end-to-end
java -cp out com.studyhub.test.VerifyIntegrationTest
```

### 3. Launch the Server
```bash
# Windows
run_server.bat

# Linux / macOS
chmod +x run_server.sh && ./run_server.sh
```

### 4. Launch Client Instances
```bash
# Windows
run_client.bat

# Linux / macOS
chmod +x run_client.sh && ./run_client.sh
```

---

## 🔑 Pre-Seeded Evaluation Accounts

For rapid 2-minute switching during group evaluation, quick-select buttons are provided in the GUI:

- **student1** / `pass123` (Role: `STUDENT`)
- **student2** / `pass123` (Role: `STUDENT`)
- **student3** / `pass123` (Role: `STUDENT`)
- **admin** / `admin123` (Role: `MODERATOR`)

---

## 🛡️ Zero External Dependencies Guarantee
Built purely on standard Java runtime libraries (`java.net.*`, `java.nio.*`, `javax.net.ssl.*`, `java.util.concurrent.*`, `java.security.*`, `java.io.*`, `java.time.*`, `javax.swing.*`). No external `.jar` files, Maven, or Gradle required.
