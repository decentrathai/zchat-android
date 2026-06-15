# ZCHAT Code-Review Checklist — recurring bug-class guards

Purpose: every PR touching the areas below must pass these. Each item exists because the class of
bug it guards has bitten us before (see `../TEST_PLAN.md` §2 and the session history). Reviewers:
treat an unchecked box in a touched area as a blocker.

> Companion to `TEST_PLAN.md`. When you add a *new* recurring failure, add a guard here AND a
> regression test there.

---

## Crypto — `screen/chat/crypto/**`, `model/ZMSG*Protocol.kt`
- [ ] Every `Base64.decode(...)` / `JSONObject(...)` / `JSONArray(...)` is wrapped and returns a **typed `ZchatResult`**, never throws to the caller/UI. *(Past bug: uncaught Base64 decode; malformed KEX crash.)*
- [ ] Shared key is validated `== 32` bytes and nonce `== 12` bytes **before** cipher init — `require()` at the boundary, no `InvalidAlgorithmParameterException`/`IllegalArgumentException` leaking out. *(Past: SharedKey/nonce length unvalidated.)*
- [ ] `decrypt()` distinguishes **AUTH-FAIL vs MALFORMED vs NO-MESSAGE** (not all collapsed to `null`) so the UI shows the right state. *(Past: tamper indistinguishable from "no ZCHAT message".)*
- [ ] HKDF salt is non-null / non-all-zero; key derivation uses context/`info` separation so different purposes yield different keys. *(Past: ECIES null salt.)*
- [ ] No broad `catch (e: Exception)` that swallows a crypto root cause — catch the specific type or rethrow typed.
- [ ] KEX/handshake: signature verified; **first-contact is TOFU** — never silently overwrite an already-established peer key from an inbound/unsigned path (KEX flags + clears `verified`; unsigned paths like group-invite **ignore** a differing key). *(Past: group-invite verification-stripping; KEX self-signed = MITM on first contact — bind to identity when shipping the identity-binding work.)*
- [ ] On any peer-key change, `setE2EVerified(false)` is cleared alongside `setE2EKeyChanged(true)`; new persistent E2E key added → also removed in `DestroyManager` + `clearE2EKeys`.

## Routing — `viewmodel/ChatViewModel.kt`, `model/ZMSGProtocol.kt`
- [ ] Any **new conversation-resolution branch** calls `setConversationMapping(...)`. *(BUG3: heuristic paths forgot to persist the mapping.)*
- [ ] A fallback/heuristic route **never spawns a second conversation** for an existing peer. *(BUG2: wrong-thread after data loss.)*
- [ ] `senderHash` is preserved through chunk reassembly (v4 RPL). *(BUG1: senderHash dropped → reply mis-routed.)*
- [ ] Unrecognized memo → a specific `unknownReason` (VERSION_MISMATCH vs HASH_NOT_IN_CACHE vs NOT_ZMSG_FORMAT), **never** a bare crash, and a known peer with a valid convId is **not** flagged "Unknown sender" just because the hash cache is cold after restart. *(BUG7.)*

## Navigation — `*Navigator.kt`, `screen/**` back/close handlers
- [ ] `popBackStack()` return value is checked; on `false` fall back to a known-good destination (`backToRoot()`), never assume the back stack is intact. *(BUG1: dark screen after `replaceAll` + unguarded pop.)*
- [ ] After a `replaceAll(...)` flow, "Close"/back resolves to a real screen (History/Home), verified by a nav test.

## Destructive / wipe — `util/DestroyManager.kt`
- [ ] **Added a new persistent store?** → it is wiped in `destroyAll()` AND asserted in `DestroyManagerTest`. The wipe must cover: Room DB, `SharedPreferences`, `EncryptedSharedPreferences` (incl. **ratchet store**), **AddressCache**, **ContactBook**, file cache, SDK data. *(BUG5: `destroyAll()` had 0 refs to ratchet/AddressCache/ContactBook.)*
- [ ] Wipe runs on a scope that **outlives the Composable** (`viewModelScope`/app scope), and `Process.killProcess()` is the **last** step, after every clear awaits completion. *(BUG5: chat-list used `rememberCoroutineScope` → killed mid-wipe.)*
- [ ] A destroy fix only ever wipes **more**, never less; PIN sub-flows (first-setup vs verify-existing) unchanged.

## Send / sync reliability — `screen/chat/**` send pipeline
- [ ] A blocked send (e.g. previous tx unconfirmed) **queues + auto-retries on next block**, it does not `throw`/toast a dead-end. There is exactly **one** sink for the proposal use-case so no caller bypasses the queue. *(BUG8.)*
- [ ] No JSON parse / blocking I/O / crypto on `Dispatchers.Main`.

## UI / Compose — `screen/chat/view/**`
- [ ] `.align()` only inside a `BoxScope` content lambda; large Composables (`ChatDetailView`, `ChatListView`) keep braces balanced (verify brace depth on large edits).
- [ ] QR render: module area ≥ ~85% of the canvas, center logo ≤ ~8–15%, and the generated bitmap still **decodes back** to the address (test-gated). *(BUG6: 240dp + 18% logo = unscannable.)*
- [ ] Clipboard of sensitive data uses the non-deprecated API and (for secrets) an auto-clear timeout.
- [ ] Trust/verified/key-changed indicators reflect current state (ideally backed by observable state, not a one-shot prefs read).

## Backend — `apps/backend/src/server.ts`
- [ ] User-supplied amounts validated (no `NaN`/negative; bounded maxTokens/prompt); balance writes are **atomic** (row-lock / transaction) so concurrent debits can't go negative.
- [ ] Value-creating endpoints (trial mint, deposit credit) require **verified proof** (wallet pubkey ownership / on-chain confirmation), never trust client assertion.
- [ ] No secrets/txids leaked in responses or `localStorage`; bearer tokens stored as keyed **HMAC**, not plain SHA-256.

---

## CI enforcement (target state)
- [ ] CI runs `:ui-lib:testZcashmainnetFossDebugUnitTest` (L1) + the Robolectric L2 suite + `apps/backend` vitest. *(Today CI runs only detekt/ktlint/checkProperties — no test task.)*
- [ ] detekt `TooGenericExceptionCaught` + `SwallowedException` **on** for `screen/chat/**` (crypto smell). `LongMethod`/`LargeClass` as warnings for `screen/chat/**`. *(Currently `active: false` in `tools/detekt.yml`.)*
- [ ] detekt `ForbiddenMethodCall`: no raw `Base64.decode`/`JSONObject(` in `crypto/**` or `model/ZMSG*` — must go through a `SafeCodec` returning `ZchatResult`.
- [ ] CI script fails if a class implementing a persistent-store interface has **0** references in `DestroyManager.kt` (guards the wipe-gap class).
- [ ] `TODO`/`FIXME` in `crypto/**` and routing fail CI (so "send-path TODO" can't be marked complete again).
