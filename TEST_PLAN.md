# ZCHAT Test Plan (living)

Owner: engineering · Status: **active campaign (2026-06-05)** · Update this file whenever a flow/bug changes.

This is the single source of truth for what is tested, what is not, and how to run it. Every bug we fix gets a row in §2 with the regression test that would have caught it.

---

## 0. How to run

```bash
# L1 — JVM unit (fast, no Android runtime)
./gradlew :ui-lib:testZcashmainnetFossDebugUnitTest

# L2/L3 — instrumented / real Base64+JSONObject (device or emulator)
./gradlew :ui-lib:connectedZcashmainnetFossDebugAndroidTest

# Backend (TypeScript)
cd /home/yourt/zchat/apps/backend && pnpm test
```

> ⚠️ **Harness constraint — read before adding a test.** `ui-lib/build.gradle.kts:24` sets
> `unitTests.isReturnDefaultValues = true`, which stubs `android.util.Base64` and `org.json.JSONObject`
> to **no-ops** in the JVM `src/test/` source set. Any class that calls `Base64`/`JSONObject`
> (`E2EEncryption`, `ZMSGProtocol`, `ZMSGGroupProtocol`, the EncryptedPrefs ratchet store) **cannot**
> be unit-tested in `src/test/` — it silently returns empty/zero and the test is meaningless. Put
> those tests in `src/androidTest/` (instrumented) or run them under Robolectric with
> `unitTests.isIncludeAndroidResources = true`. This is why `ZMSGProtocolTest` (703L) lives in
> `androidTest/`. **The test taxonomy below routes each class to the source set that can actually run it.**

---

## 1. Layered taxonomy

| Layer | Where | Use for |
|---|---|---|
| **L1 jvm-unit** | `ui-lib/src/test/` | Pure logic with NO `Base64`/`JSONObject`: ratchet counter/ordering math, `MessageRouter` decisions, chunk split/join, `MemoDisplayText` bounds, BigDecimal serde, state machines. |
| **L2 robolectric/instrumented-light** | `ui-lib/src/androidTest/` (+ Robolectric in CI) | Real `Base64`/`JSONObject` crypto+protocol: `E2EEncryption`, `ZMSGProtocol`, `ZMSGGroupProtocol`, KEX, EncryptedPrefs ratchet store. Compose component tests (bubbles, dialogs, QR, banners) via `createComposeRule()`. |
| **L3 instrumented (emulator)** | `ui-lib/src/androidTest/` | Real nav/storage/lifecycle: transaction-detail Close-after-send (BUG1), `DestroyManager` wipe (BUG5), prefs round-trip, send-queue FIFO (BUG8), call readiness (BUG9). |
| **L4 two-user device E2E** | manual script §3 | Full 2-identity crypto-comms path + edge/abuse cases. Release gate. |

---

## 2. Coverage matrix + known-bug regression map

Status: ✅ exists & adequate · ⚠️ exists, extend · ❌ TODO (priority)

| Class / flow | Layer | Test file | Status | Bug it guards |
|---|---|---|---|---|
| `crypto/ratchet/E2ERatchet` | L1 | `test/.../ratchet/E2ERatchetTest.kt` | ✅ (+DoS regression added 2026-06) | ratchet DoS; out-of-order; restore re-decrypt |
| `crypto/ratchet/EncryptedPrefsRatchetStateStore` | L1 | `test/.../ratchet/EncryptedPrefsRatchetStateStoreTest.kt` | ⚠️ +missing-field typed-error | wipe/orphan |
| `crypto/ratchet/CiphertextWireFormat` | L1 | `test/.../ratchet/CiphertextWireFormatTest.kt` | ✅ | frame forgery |
| `routing/MessageRouter` | L1 | `test/.../routing/MessageRouterTest.kt` | ⚠️ +wrong-thread, +mapping | BUG2/3 (wrong-thread, missing `setConversationMapping`) |
| `crypto/QuantumShield(State)` | L1 | `test/.../crypto/QuantumShield*Test.kt` | ✅ | PSK fail-closed |
| `crypto/E2EPSK` | L1 | `test/.../crypto/E2EPSKTest.kt` | ✅ | PSK derivation |
| file sharing (encrypt/upload/download/integrity) | L1 | `test/.../filesharing/*Test.kt`, `crypto/File*Test.kt` | ✅ | ZFILE pipeline |
| `model/ZFILEMessage`, `ZBootMessage` | L1 | `test/.../model/Z*Test.kt` | ✅ | raw-memo leak (forDisplay) |
| `model/ZMSGProtocol` | L2 | `androidTest/.../model/ZMSGProtocolTest.kt` (703L) | ⚠️ +chunk senderHash, +version-compat | BUG1 (chunk), BUG7 (recognition) |
| `viewmodel/` message routing | L2 | `androidTest/.../viewmodel/MessageRoutingTest.kt` (495L) | ⚠️ +first-contact, +tier collision | BUG7, DEC-015 |
| `datasource/ZchatPreferences` | L2 | `androidTest/.../datasource/ZchatPreferencesTest.kt` | ⚠️ +verified/keyChanged keys | trust-state lifecycle |
| `crypto/E2EEncryption` (KEX/ECDH/sign/ECIES) | L2 | `androidTest/.../crypto/E2EEncryptionTest.kt` | ❌ **P0** | key/nonce len, Base64 throw, ECIES salt, auth-vs-malformed, MITM/TOFU, replay |
| `model/ZMSGGroupProtocol`, `GroupViewModel` | L2 | `androidTest/.../model/ZMSGGroupProtocolTest.kt` | ❌ **P0** | group ECIES, malformed JSON, nonce len, GROUP_LEAVE, history-after-restart |
| `util/DestroyManager` | L3 | `androidTest/.../util/DestroyManagerTest.kt` | ❌ **P0** | **BUG5** — wipe gap (0 refs to ratchet/AddressCache/ContactBook) + chat-list no-crash |
| transaction-detail nav | L3 | `androidTest/.../TransactionDetailNavTest.kt` | ❌ **P0** | **BUG1** — Close-after-send crash |
| `QrCodeView` / `ZchatReceiveView` | L2 | `androidTest/.../QrCodeRenderTest.kt` | ❌ | **BUG6** — module-area >85%, logo <15%, decodes back |
| send queue / `CreateChunkedMessageProposalUseCase` | L3 | `androidTest/.../SendQueueTest.kt` | ❌ | **BUG8** — FIFO, non-blocking, auto-retry |
| calls (`CallViewModel`, `nostr/*`) | L3/E | `androidTest/.../CallInitTest.kt`, `CallReadinessTest.kt` | ❌ | **BUG4/9** — keys in 1st handshake, readiness error |
| `model/MemoDisplayText` | L1 | `test/.../model/MemoDisplayTextTest.kt` | ❌ | `indexOf` bounds safety |
| Backend AI/deposit (`apps/backend`) | vitest | `apps/backend/src/*.test.ts` | ❌ **P0** | trial mint w/o wallet proof, non-atomic debit, deposit-verify |

---

## 3. Manual two-user device script (release gate)

Two identities exercised against each other. Device A = Honor `[REDACTED-SERIAL]`; Device B = emulator. `PKG=co.electriccoin.zcash`. Observe via `logcat -s ZCHAT_V4 ZMSG KEX` (+ `uiautomator dump` — chat screens are FLAG_SECURE so screenshots are blocked).

**Happy path:** S0 fresh onboarding ×2 → S1 QR exchange (scan — BUG6 gate) → S2 KEX handshake (shared key matches, B not "unknown sender" — BUG7 gate) → S3 send→on-chain→receive → S4 reply threading (same thread — BUG2 gate) → S5 reaction + read receipt → S6 file share (SHA-256 matches) → S7 group create/invite/leave + restart history re-decrypt.

**Edge / abuse:** E1 malformed memo (no crash) · E2 oversized >512B (chunk+reassemble, senderHash intact — BUG1) · E3 three rapid sends before confirm (all FIFO, no block — BUG8) · E4 key rotation mid-convo (banner, old msgs still decrypt) · E5 replay captured KEX/msg (rejected) · E6 MITM tamper KEX pubkey byte (verify=false, no auto-trust) · E7 restore seed on new device (history re-derives) · E8 offline→send→online (queued then auto-sent) · E9 low balance (clear error, draft preserved) · E10 rapid open/close detail after send (no dark screen — BUG1) · E11 destroy-all from chat-list (no crash; then `run-as $PKG ls -R shared_prefs databases files` shows NO ratchet/address-cache/contact entries — BUG5).

> The full ADB-runnable script with per-step assertions lives in `docs/two-user-e2e.sh` (generated alongside the campaign).

---

## 4. Release gate (all must pass)

- [ ] L1 + L2 suites green (`testZcashmainnetFossDebugUnitTest` + `connectedAndroidTest`)
- [ ] L3 wipe (BUG5) + nav (BUG1) green
- [ ] L4 two-user happy path (S0–S7) + edge cases E1–E11 observed-pass
- [ ] Backend P0 vitest green
- [ ] `detektAll` + `ktlint` clean
- [ ] No new entry in §2 left at ❌ for a shipped flow

---

## 5. Recurring bug-class guards

See `docs/CODE_REVIEW_CHECKLIST.md` — the per-area review gates that stop these classes returning. CI enforcement (detekt rules, the DestroyManager-vs-test consistency check, the test job) is tracked there.
