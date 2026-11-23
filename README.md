# StudyHub

A local desktop workspace for study groups: conversations, rooms, typing indicators, shared files, and persistent accounts. Built entirely with **Java SE 17+**, **Swing**, and **Java2D**. No external libraries, JARs, build tools, or cloud account are required.

## Start on two laptops

Both laptops need a full **JDK 17 or newer** (including `javac` and `keytool`) and must be on the same trusted Wi-Fi/LAN. Give each laptop its own repository copy. Do **not** copy the host's generated `certs/` or `data/` to a joining laptop.

1. On both laptops, run `build.bat` (Windows), or `bash build.sh` (macOS/Linux). Builds target Java 17 even when compiled with a newer JDK.
2. On **Laptop A**, mark your trusted Windows Wi-Fi connection as **Private**. Right-click **`allow_wifi_ports.bat` → Run as administrator** once. This adds named inbound rules for TCP **9000, 9001, 9443** and UDP **9876, 9877**, restricted to the local subnet on **Private/Domain** profiles. It does not disable the firewall. Organization policy may still restrict connections.
3. Launch **`run_client.bat`** on both laptops (or `bash run_client.sh`). Windows launches the app with `javaw`, without leaving a console window open. The scripts work when launched from another directory and compile automatically if classes are missing.
4. On **Laptop A**, choose **Host a Workspace**, enter a name such as **CS Networking Study Group**, choose **Create Account**, and enter your display name, username, and password. **Host & Create Account** starts all services in the background and signs you in. Existing members can use **Host & Sign In**. You can also start the host separately with **Start Workspace** before signing in.
5. On **Laptop B**, stay on **Join a Workspace**. Within a few seconds, select Laptop A's workspace card, then create an account or sign in to an account on **that host**. Cards show workspace name, reachable host IPv4, online count, and an active indicator.
6. If no card appears, on Laptop A click **Invite to workspace → Copy Address**. On Laptop B choose **Connect with IP Address**, paste that IPv4 (for example `192.168.1.42`), and sign in. Every service uses this selected address; the client does not fall back to localhost.
7. Open the same room on both laptops. Send a message, type to see the other member's indicator, upload a file using **Share File** or drag and drop, then download it on the other laptop. Keep Laptop A's app running while hosting; signing out leaves its host online until the app exits.

Accounts belong to the host workspace. Usernames use 3–24 letters, digits, underscores, or periods; new passwords need at least 6 characters. Sign In never silently creates an unknown account. Display names and study status can be changed in **Settings**. Settings also opens your Downloads folder, connection security details, or the workspace switcher.

## When Wi-Fi discovery or connection fails

- Set both laptops to the same ordinary LAN, rather than a guest SSID. **Connect with IP Address** bypasses blocked broadcast discovery; it cannot bypass a router's client/AP isolation that blocks all laptop-to-laptop traffic. Disable that isolation on your own router or use another trusted LAN/hotspot.
- Discovery listens on UDP **9876**, with address reuse and broadcast enabled. Run the firewall helper on the joining laptop too if Windows blocks incoming discovery. Allow the JDK application if your OS asks. Configure equivalent local-subnet rules on macOS/Linux if needed.
- Windows VPN, security software, or managed-network policy can restrict local traffic. The host enumerates active physical IPv4 adapters, prefers private Wi-Fi addresses, and broadcasts through each adapter's directed subnet broadcast in addition to `255.255.255.255` and local loopback.
- With multiple adapters, use the IPv4 on the **same subnet as your friend**. The copied address prefers Wi-Fi; the beacon sent through each adapter includes that adapter's address. The listener trusts the actual packet sender, using the advertised IPv4 for loopback/fallback, and deduplicates by IP plus workspace name.
- A port already in use is reported inline when hosting. Close the conflicting host/application or join an existing StudyHub host. The app keeps the documented ports; it does not silently switch them. Failed embedded startup releases services already started so you can retry.
- Windows `netstat -ano | findstr :9001` can help identify a port conflict. A System/PID 4 listener belongs to Windows; do not terminate System. Resolve the service conflict or use another host machine.
- Changing Wi-Fi or DHCP address may require selecting the host again. Client chat reconnects to the selected host after a temporary disconnection. A new host certificate requires fingerprint verification as described below.

## Connection security and local data

Click the small green lock beside the workspace name, or **Settings → Connection Security**, to view the actual TLS protocol, cipher, X.509 subject/issuer, validity, and selectable SHA-256 certificate fingerprint.

Authentication and registration use **TLS 1.3**. On first host startup, the JDK's `keytool` creates a self-signed X.509 certificate with **CN=StudyHubServer** in `certs/studyhub_keystore.p12`. A joining client needs no pre-shared keystore or truststore: it inspects the certificate's identity, validity, and signature, then saves its real fingerprint using **Trust On First Use (TOFU)**. Later connections to the same IP/port must match the saved pin.

Client pins live in **`~/.studyhub/known_hosts.properties`**. For controlled testing, override this using `-Dstudyhub.knownHosts=<path>`. A changed pin is deliberately rejected before credentials are sent. Compare the displayed fingerprint with the host on your first connection. If a host intentionally replaces its certificate, verify the new fingerprint with its owner before removing **only that endpoint's entry** from the pins file. There is no trust-all fallback. An expired or wrong-identity certificate must fail rather than bypass verification.

Messages are encrypted with **AES-128-GCM**, including authentication tags and fresh nonces. The persistent workspace key in `certs/workspace.key` is delivered over TLS to authenticated members, so different members and reconnecting clients can read the same conversations. Existing CBC payloads remain supported for history compatibility **if their original key still exists**. Older builds generated disposable per-login keys; ciphertext whose old key was discarded cannot be recovered.

This is **host-managed workspace encryption**, not independently verified end-to-end encryption: the host holds the shared key, and all authenticated workspace members receive it. The message lock tooltip describes workspace encryption accurately. Authentication is protected by TLS; NIO metadata/session tokens, UDP presence, and file-transfer streams still use the original LAN protocols. SHA-256 verifies file bytes and detects the evaluation tamper simulation; it does not encrypt transferred files or authenticate an attacker-controlled network. Use StudyHub on a trusted local network; this implementation is not an Internet-facing or hostile-network service. The seeded evaluation accounts below intentionally remain enabled as requested.

| Local path | Purpose |
|---|---|
| `data/` | Host account records (salted credential hashes), rooms, topics, pins, transcripts, vault index |
| `certs/` | Host certificate/private key and persistent workspace message key |
| `shared_storage/` | Host's room files |
| `downloads/<username>/` | Client downloads with integrity verification and duplicate-safe filenames |
| `~/.studyhub/known_hosts.properties` | Client TOFU certificate pins |

Keep the host's `certs/`, `data/`, and `shared_storage/` together when backing up a workspace. Runtime data, keys, downloads, logs, and compiled classes are ignored by Git.

## Developer and evaluation mode

Everyday workspaces hide the network inspector and tamper controls. Press **Ctrl+Shift+I** (including on macOS), or enable **Settings → Developer Diagnostics**, to open the animated drawer. It retains all five tabs:

| Member | Package | Evaluation focus |
|---|---|---|
| 1 | `com.studyhub.nio` | Selector loop, framed chat, buffer flip/compact, growing fragmented history buffers |
| 2 | `com.studyhub.transfer` | TCP/FileChannel upload/download, streaming SHA-256, progress, verified files |
| 3 | `com.studyhub.security` | TLS 1.3, X.509 identity/validity, TOFU fingerprint pinning, AES-GCM |
| 4 | `com.studyhub.udp` | Per-interface discovery, actual sender IPv4, typing/presence, stale sequence rejection |
| 5 | `com.studyhub.concurrent` | Worker pool, asynchronous file search and HTTP link previews |

Inside **Tab 3 — TLS & Crypto**, enable **Simulate MITM File Tampering** and upload a nonempty file. The transfer server flips a byte and rejects the SHA-256 mismatch. Disable the toggle for a normal upload. Closing diagnostics automatically clears tamper simulation. This control is available only inside the drawer; there are no demo buttons in the login screen or main room header.

Backend evaluation accounts remain available: `student1`, `student2`, `student3` with password `pass123`, and `admin` with password `admin123`. They are documented here for evaluators and are never prefilled or advertised by the app.

A suggested ten-minute evaluation: join from the second laptop, send encrypted chat and demonstrate typing, upload/download a file, show the certificate fingerprint, open diagnostics and demonstrate tamper rejection, then inspect the worker pool and persisted history.

## Headless host

The original standalone entry point remains available:

```text
run_server.bat
# macOS/Linux:
bash run_server.sh
# Or directly (optional workspace name):
java -cp out com.studyhub.server.StudyHubServerMain "CS Networking Study Group"
```

Each host process exposes wildcard listeners on TCP **9443, 9000, 9001** and UDP **9877**, and broadcasts discovery to UDP **9876**. Avoid running the embedded host and standalone host simultaneously on one machine.

## Build and verify

```text
build.bat
# or: bash build.sh
java -cp out com.studyhub.test.VerifyIntegrationTest
```

The dependency-free headless suite starts a real server on the original ports and exits nonzero on failure. It checks discovery's non-loopback IPv4 when available, TLS and authentication, file upload/download and tamper rejection, NIO fragmentation and history, thread-pool HTTP work, persistence, a separate fresh client process without `certs/`, correct saved fingerprints, rejection of changed/expired/wrong-identity certificates, cross-member decryption, live two-member chat/typing/profile updates, and large network frames. Test TOFU pins and temporary certificate fixtures stay under `build/`; the test uses the preferred LAN IPv4 when available. Run with all required ports free. Tests create evaluation records/files in the host's ignored runtime folders; use a disposable repository copy for a pristine workspace.

For graphical layout and interaction checks on a desktop:

```text
java -cp out com.studyhub.test.UiSmokeTest
java -cp out com.studyhub.test.ServerLifecycleTest
```

This opens and closes test windows, verifies onboarding/defaults, IP validation, hidden diagnostics, shortcut activation, all five tabs, and tamper-state reset, and writes rendered previews to `build/ui-review/`. It does not host a server or require seeded credentials. `ServerLifecycleTest` separately checks port-conflict rollback, successful embedded-host retry, discovery of the chosen workspace name, TLS sign-in, repeated-start idempotence, and shutdown.

Compilation targets Java 17 using only JDK APIs. Passing a same-machine or isolated-network suite does not by itself prove a router/firewall path between two physical laptops; perform the two-laptop steps above on the actual Wi-Fi before an evaluation.
