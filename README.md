# Collaborative Study Hub & File Sharing Platform

A production-grade, **zero-dependency** Java networking platform built strictly with standard **Java SE 17+ (JDK 21)**.
Designed with modular package isolation to demonstrate fundamental and advanced computer networking concepts across 5 specialized domains — now with a **disk persistence engine**, self-service registration, and real-world collaboration ergonomics.

---

## 🏛️ System Architecture & 5-Module Isolation

```
src/com/studyhub/
├── common/                  # NetworkConfig, ProtocolConstants, PacketLogEvent
│   └── DataPersistenceManager   # java.nio.file flat-file storage engine (data/)
├── nio/         [MEMBER 1]  # Java NIO Non-Blocking Multiplexing (Port 9000)
├── transfer/    [MEMBER 2]  # TCP Sockets & NIO FileChannel Vault (Port 9001)
├── security/    [MEMBER 3]  # TLSv1.3 Authentication & AES/SHA-256 (Port 9443)
├── udp/         [MEMBER 4]  # UDP Connectionless Beacons & Pulse (Port 9876 & 9877)
├── concurrent/  [MEMBER 5]  # FixedThreadPool & HTTP Link Preview Engine
├── server/                  # StudyHubServerMain (Unified Bootstrapper + Shutdown Hook)
├── test/                    # VerifyIntegrationTest (headless end-to-end suite)
└── ui/                      # Swing Client GUI & Real-Time Network Inspector
```

---

## 👥 5-Member 2-Minute Evaluation Talking Points

| Member | Package | Key Concepts Demonstrated | Demonstration Highlights |
|---|---|---|---|
| **Member 1** | `com.studyhub.nio` | Non-blocking I/O, `Selector`, `ServerSocketChannel`, `SocketChannel`, `ByteBuffer` state transitions (`flip()`, `compact()`, `clear()`), 4-byte big-endian length prefix framing. | Show the single-threaded event loop (`"NIO-Selector-EventLoop"`), show `position`/`limit`/`capacity` tracking in the Network Inspector tab, and explain how `compact()` preserves trailing frame fragments. Show `HISTORY_BATCH` replaying 50 persisted messages to a late joiner. |
| **Member 2** | `com.studyhub.transfer` | TCP `ServerSocket`, streaming I/O, `FileChannel` block transfers, chunked memory buffers, SHA-256 digest computation. | Drag a PDF/ZIP onto the chat to upload it, or click **Share File**. Watch live byte-progress, then **Open File** straight from the Study Material Card. Explain `StandardOpenOption`, zero-corruption chunking, and throughput metrics in the FileChannel Inspector tab. |
| **Member 3** | `com.studyhub.security` | `SSLServerSocket`, `SSLSocket`, TLSv1.3 handshake, PKCS12 keystore bootstrapping, X.509 certificate inspection, AES-128-CBC encryption, salted SHA-256 credential storage. | Click **Simulate Tamper (Demo)** during file upload to demonstrate instant SHA-256 mismatch rejection (`UPLOAD_TAMPERED`). Show X.509 Subject DN, cipher suites, and AES encryption badge in chat. Show **Create Student Account** writing a salted record to `data/users.db`. |
| **Member 4** | `com.studyhub.udp` | Connectionless UDP datagrams, `DatagramSocket`, `DatagramPacket`, LAN broadcast (`255.255.255.255`), `SocketTimeoutException` handling, sequence number tracking. | Show server auto-discovered in the login dialog without typing an IP. Show real-time `[TYPING...]` status on keypress, switch your **live study status** (Available / Focusing / In Lab), and demonstrate out-of-order stale packet dropping in telemetry. |
| **Member 5** | `com.studyhub.concurrent` | `ExecutorService`, `ThreadPoolExecutor`, `Callable<T>`, `Future<T>`, `URI`, `URL`, `InetAddress` DNS resolution, `HttpURLConnection`. | Post an HTTP/HTTPS link in chat (e.g., `https://example.com`). Observe async background resolution without stalling the NIO thread, generating a rich preview card with IP address, HTTP status, and HTML title. Type in the vault search box to watch `FileSearchCallable` filter results off the EDT. |

---

## 🔌 Port Allocations & Wire Protocols

| Port | Transport | Protocol | Purpose | Wire Format Sample |
|---|---|---|---|---|
| **9443** | TCP (TLSv1.3) | TLS Authentication | Cipher negotiation, session tokens & self-service registration | `AUTH\|<username>\|<sha256Password>` → `AUTH_OK\|<token>\|<role>\|<aesKey>\|<displayName>`<br>`REGISTER\|<username>\|<sha256Password>\|<displayName>` → `REG_OK\|<username>\|<displayName>\|<role>\|<totalAccounts>` |
| **9000** | TCP (NIO) | Length-Prefixed TCP | Non-blocking chat, room multiplexing, history replay | `[4-byte len]MSG\|#general\|PLAIN\|Hello World!\|<replyRef>`<br>→ `CHAT\|#general\|student1\|PLAIN\|Hello World!\|14:02:11\|<replyRef>`<br>`JOIN\|#room` → `HISTORY_BATCH\|#room\|<n>\|<entry>…` |
| **9001** | TCP (Stream) | TCP File Transfer | `FileChannel` disk storage & SHA-256 validation | `UPLOAD\|<token>\|<room>\|<filename>\|<size>\|<sha256>\|<tamper>`<br>→ `UPLOAD_OK\|<sha256>\|<storedName>\|<timestamp>` |
| **9876** | UDP (Broadcast) | Datagram Beacon | Periodic LAN server discovery (3000ms) | `BEACON\|<seq>\|<name>\|9443\|9000\|9001\|9877\|<users>` |
| **9877** | UDP (Datagram) | Presence Pulse | Fire-and-forget typing/online heartbeat & study status | `PULSE\|<seq>\|<username>\|<room>\|<ONLINE\|TYPING\|AVAILABLE\|FOCUS\|IN_LAB>` |

### NIO Server → Client Event Types

| Event | Format | Purpose |
|---|---|---|
| `CHAT` | `CHAT\|<room>\|<sender>\|<encFlag>\|<content>\|<time>\|<replyRef>` | Chat / DM delivery |
| `USER_LIST` | `USER_LIST\|<room>\|<user:status>…` | Room roster + presence |
| `LINK_CARD` | `LINK_CARD\|<room>\|<url>\|<ip>\|<status>\|<title>` | Async HTTP preview |
| `FILE_ADDED` | `FILE_ADDED\|<room>\|<filename>\|<size>\|<uploader>\|<sha256>` | New vault upload |
| `VAULT_LIST` | `VAULT_LIST\|<room>\|<id>\|<original>\|<stored>\|<size>\|<sha>\|<uploader>\|<ts>` | Rebuilds the vault on join |
| `HISTORY_BATCH` | `HISTORY_BATCH\|<room>\|<count>\|<entry>…` | Replays the last 50 persisted messages |
| `ROOM_LIST` | `ROOM_LIST\|<room>:<topic>…` | Persisted room list (with topics) |
| `PINNED` | `PINNED\|<room>\|<payload>` | Pinned banner (empty payload unpins) |
| `PRESENCE_UPDATE` | `PRESENCE_UPDATE\|<room>\|<user>\|<status>` | Typing / study status |
| `SYSTEM` | `SYSTEM\|<text>` | Server notices |

New client → server commands: `CREATE_ROOM\|<room>\|<topic>`, `SET_TOPIC\|<room>\|<topic>`, `PIN\|<room>\|<payload>`, `HISTORY\|<room>`.

> All new fields are **appended** to the original frames, so the documented protocol samples above still parse exactly as before.

---

## 🗄️ Disk Persistence Engine (`data/`)

`com.studyhub.common.DataPersistenceManager` keeps the hub durable with lightweight, thread-safe flat files
(a fair `ReentrantReadWriteLock` guards an in-memory mirror; a daemon flusher thread writes every 400 ms;
index files are re-written atomically via temp file + `ATOMIC_MOVE`).

| File | Format | Contents |
|---|---|---|
| `data/users.db` | `username\|salt:salted_sha256_hash\|role\|displayName` | The 4 pre-seeded demo accounts plus every self-registered student |
| `data/rooms.db` | `room\|topic\|pinnedPayload` | Custom study rooms, topics and pinned banners |
| `data/history_<room>.log` | `kind\|timestamp\|sender\|role\|encFlag\|payload\|replyRef` | Append-only transcript (chat, link cards, file cards) |
| `data/vault_index.db` | `fileId\|room\|uploader\|original\|stored\|size\|sha256\|timestamp` | Metadata for every artefact inside `shared_storage/` |

* Passwords are never stored verbatim: each account gets a random 16-byte salt and stores
  `sha256(salt + ":" + sha256(password))`.
* On every `JOIN` the server replays the **last 50** persisted messages over the same `SocketChannel`
  (`HISTORY_BATCH`) so late joiners immediately see the conversation.
* The Room Study Vault is repopulated from `vault_index.db` the moment a client joins.
* `StudyHubServerMain` registers a `Runtime.getRuntime().addShutdownHook(...)` that flushes every
  pending write ("`[SHUTDOWN] Persistent state flushed to data/`").

---

## 🚀 Real-World Usability Features

1. **Self-service registration** — `Create Student Account` tab on the login portal (duplicate usernames and
   malformed usernames are rejected by the TLS auth service), while the 1-click evaluation pills
   (`student1`, `student2`, `student3`, `admin`) stay in place for fast demos.
2. **Live study status** — set `Available`, `Focusing / Do Not Disturb`, or `In Lab` from the profile bar;
   it is broadcast with the existing UDP `PULSE` protocol on port 9877.
3. **Reply / quote & moderator pinning** — hover any message for `Reply` / `Pin to Room` (or right-click).
   Replies render a quoted snippet, and pins show a sleek banner above the room (moderator-only, enforced server-side).
4. **Unread badges & alerts** — inactive rooms show a red count (`#networking [3]`), and `@username`
   mentions, DMs and file shares raise a toast plus a subtle system beep.
5. **Search** — `Ctrl+F` filters/highlights chat messages, and the vault search bar filters via
   `FileSearchCallable` on the thread pool (debounced, never blocking the UI).
6. **Drag & drop sharing** — drop a PDF, ZIP or code file from the desktop straight onto the chat feed;
   uploads stream over port 9001 with live byte progress and SHA-256 verification.
7. **Downloads manager** — files land in `downloads/<username>/` with duplicate handling
   (`notes (1).pdf`), plus one-click **Open File** and **Open Downloads Folder** via `java.awt.Desktop`.
8. **Resilient connection** — if the server restarts or the network blips, a non-blocking
   `Reconnecting to Study Hub…` banner appears, the client re-authenticates over TLS 9443 and
   re-opens the NIO session, rejoining the same room without losing the window.
9. **Switch user / log out** — the profile bar's `⇄ Switch User` button cleanly tears down networking
   and returns to the login portal so several group members can share one machine.

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
# Runs the full headless integration test verifying all 5 modules end-to-end
java -cp out com.studyhub.test.VerifyIntegrationTest
```
The suite boots a live server on all 5 ports and asserts **80 checks** across 8 phases, including
registration over TLS, room creation, history persistence/replay and vault-index durability.

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
- **admin** / `admin123` (Role: `MODERATOR` — required to pin room messages)

Any new account created through the GUI is stored in `data/users.db` and works on every subsequent start.

---

## 🛡️ Zero External Dependencies Guarantee
Built purely on standard Java runtime libraries (`java.net.*`, `java.nio.*`, `javax.net.ssl.*`,
`java.util.concurrent.*`, `java.security.*`, `java.io.*`, `java.time.*`, `javax.swing.*`, `java.awt.*`).
**No external `.jar` files, Maven, or Gradle required.**
