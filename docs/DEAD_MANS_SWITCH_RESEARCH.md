# Dead Man's Switch - Implementation Research

## Feature Overview

A "Dead Man's Switch" self-destruct timer that automatically destroys all ZCHAT data if not cancelled within a specified time period. Designed for high-risk users who may be detained, arrested, or otherwise unable to manually destroy their data.

### User Flow

```
1. User activates timer (e.g., 2 hours)
2. Timer runs in background (survives app kill, device sleep)
3. User can cancel via:
   a) Stopping timer in app UI
   b) Entering secret code on same device
   c) Sending cancellation transaction from another device
4. If timer expires without cancellation:
   → DestroyAll() executes automatically
   → All data wiped, app force-killed
```

---

## Technical Implementation

### 1. Android Implementation Options

| Approach | Reliability | Battery | Doze Mode | Exact Timing |
|----------|-------------|---------|-----------|--------------|
| **AlarmManager (setExactAndAllowWhileIdle)** | HIGH | Medium | YES | YES |
| WorkManager (PeriodicWork) | Medium | Low | Partial | NO |
| Foreground Service + Handler | HIGH | HIGH | YES | YES |
| JobScheduler | Medium | Low | NO | NO |

**Recommended: Hybrid Approach**
- Primary: `AlarmManager.setExactAndAllowWhileIdle()` - survives Doze mode
- Backup: `WorkManager` periodic check every 15 minutes
- Recovery: `BroadcastReceiver` for `BOOT_COMPLETED` to restore timer

### 2. Required Components

```
┌─────────────────────────────────────────────────────────────┐
│                    DeadMansSwitchManager                     │
├─────────────────────────────────────────────────────────────┤
│ + startTimer(durationMs: Long, cancelCode: String)          │
│ + cancelTimer()                                              │
│ + cancelWithCode(code: String): Boolean                     │
│ + getRemainingTime(): Long?                                  │
│ + isTimerActive(): Boolean                                   │
│ + setRemoteCancelEnabled(enabled: Boolean)                  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    DeadMansSwitchReceiver                    │
│              (BroadcastReceiver)                             │
├─────────────────────────────────────────────────────────────┤
│ Actions:                                                     │
│ - ACTION_TIMER_EXPIRED → Execute DestroyAll                 │
│ - BOOT_COMPLETED → Restore timer if active                  │
│ - TIME_SET/TIMEZONE_CHANGED → Recalculate timer             │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    DeadMansSwitchWorker                      │
│              (CoroutineWorker - Backup)                      │
├─────────────────────────────────────────────────────────────┤
│ - Runs every 15 minutes                                      │
│ - Checks if timer should have expired                       │
│ - Fallback if AlarmManager fails                            │
└─────────────────────────────────────────────────────────────┘
```

### 3. Persistent Storage Schema

```kotlin
// In ZchatPreferences.kt - add these fields

// Dead Man's Switch
private const val DMS_ENABLED = "dms_enabled"
private const val DMS_EXPIRY_TIME = "dms_expiry_time"        // System.currentTimeMillis() when timer expires
private const val DMS_CANCEL_CODE_HASH = "dms_cancel_code_hash"  // SHA-256 of cancel code
private const val DMS_REMOTE_CANCEL_ENABLED = "dms_remote_cancel"
private const val DMS_REMOTE_CANCEL_AMOUNT = "dms_remote_cancel_amount"  // Zatoshi amount for remote cancel
```

### 4. AlarmManager Implementation

```kotlin
// DeadMansSwitchManager.kt

class DeadMansSwitchManager(
    private val context: Context,
    private val zchatPreferences: ZchatPreferences,
    private val destroyManager: DestroyManager
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val ACTION_TIMER_EXPIRED = "co.electriccoin.zcash.DMS_EXPIRED"
        const val ACTION_CHECK_TIMER = "co.electriccoin.zcash.DMS_CHECK"
        private const val REQUEST_CODE_EXPIRE = 1337
        private const val REQUEST_CODE_CHECK = 1338
    }

    fun startTimer(durationMs: Long, cancelCode: String) {
        val expiryTime = System.currentTimeMillis() + durationMs

        // Store in preferences
        zchatPreferences.setDmsEnabled(true)
        zchatPreferences.setDmsExpiryTime(expiryTime)
        zchatPreferences.setDmsCancelCodeHash(hashCode(cancelCode))

        // Schedule exact alarm
        val intent = Intent(context, DeadMansSwitchReceiver::class.java).apply {
            action = ACTION_TIMER_EXPIRED
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_EXPIRE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // setExactAndAllowWhileIdle works even in Doze mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    expiryTime,
                    pendingIntent
                )
            } else {
                // Fallback: request permission or use inexact alarm
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    expiryTime,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                expiryTime,
                pendingIntent
            )
        }

        // Also schedule backup WorkManager check
        scheduleBackupWorker()
    }

    fun cancelTimer() {
        // Cancel alarm
        val intent = Intent(context, DeadMansSwitchReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_EXPIRE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let { alarmManager.cancel(it) }

        // Clear preferences
        zchatPreferences.setDmsEnabled(false)
        zchatPreferences.clearDmsExpiryTime()
        zchatPreferences.clearDmsCancelCodeHash()

        // Cancel backup worker
        WorkManager.getInstance(context).cancelUniqueWork("dms_backup_check")
    }

    fun cancelWithCode(code: String): Boolean {
        val storedHash = zchatPreferences.getDmsCancelCodeHash() ?: return false
        if (hashCode(code) == storedHash) {
            cancelTimer()
            return true
        }
        return false
    }

    private fun hashCode(code: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun scheduleBackupWorker() {
        val workRequest = PeriodicWorkRequestBuilder<DeadMansSwitchWorker>(
            15, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES  // Flex interval
        )
            .setConstraints(Constraints.Builder().build())
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                "dms_backup_check",
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
    }
}
```

### 5. BroadcastReceiver Implementation

```kotlin
// DeadMansSwitchReceiver.kt

class DeadMansSwitchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val zchatPreferences = // Get from DI or create
        val destroyManager = // Get from DI or create

        when (intent.action) {
            DeadMansSwitchManager.ACTION_TIMER_EXPIRED -> {
                // Verify timer should have expired (prevent spoofed intents)
                if (zchatPreferences.isDmsEnabled()) {
                    val expiryTime = zchatPreferences.getDmsExpiryTime()
                    if (expiryTime != null && System.currentTimeMillis() >= expiryTime) {
                        // Execute destruction
                        destroyManager.destroyAll(context)
                    }
                }
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                // Restore timer after device reboot
                restoreTimerAfterBoot(context, zchatPreferences)
            }

            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                // Recalculate timer (system time may have changed)
                recalculateTimer(context, zchatPreferences)
            }
        }
    }

    private fun restoreTimerAfterBoot(context: Context, prefs: ZchatPreferences) {
        if (!prefs.isDmsEnabled()) return

        val expiryTime = prefs.getDmsExpiryTime() ?: return
        val now = System.currentTimeMillis()

        if (now >= expiryTime) {
            // Timer already expired while device was off
            val destroyManager = // Get from DI
            destroyManager.destroyAll(context)
        } else {
            // Reschedule alarm for remaining time
            val dmsManager = DeadMansSwitchManager(context, prefs, destroyManager)
            dmsManager.rescheduleExistingTimer()
        }
    }
}
```

### 6. AndroidManifest.xml Additions

```xml
<!-- Permissions -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />

<!-- Receiver -->
<receiver
    android:name=".service.DeadMansSwitchReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="co.electriccoin.zcash.DMS_EXPIRED" />
    </intent-filter>
</receiver>

<receiver
    android:name=".service.BootReceiver"
    android:exported="true"
    android:enabled="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
        <action android:name="android.intent.action.TIME_SET" />
        <action android:name="android.intent.action.TIMEZONE_CHANGED" />
    </intent-filter>
</receiver>
```

### 7. Remote Cancellation via Zcash Transaction

```kotlin
// In ChatViewModel.kt - extend checkForRemoteKill

private fun checkForRemoteDmsCancel(amountZatoshi: Long, memo: String?, txId: String) {
    if (txId in processedDmsCancelTxIds) return
    if (!zchatPreferences.isDmsRemoteCancelEnabled()) return

    processedDmsCancelTxIds.add(txId)

    val cancelAmount = zchatPreferences.getDmsRemoteCancelAmount()
    if (amountZatoshi != cancelAmount) return

    // Format: ZCHAT_DMS_CANCEL:<code>
    if (!memo?.trim()?.startsWith("ZCHAT_DMS_CANCEL:") == true) return

    val code = memo.trim().removePrefix("ZCHAT_DMS_CANCEL:")
    if (deadMansSwitchManager.cancelWithCode(code)) {
        // Show notification that DMS was cancelled remotely
        showNotification("Dead Man's Switch cancelled via remote transaction")
    }
}
```

---

## Android Policy Compliance

### Battery Optimization

**Issue:** Android aggressively kills background processes and delays alarms in Doze mode.

**Solutions:**
1. `setExactAndAllowWhileIdle()` - Works in Doze but limited to 1 alarm per 9 minutes
2. Request battery optimization exemption (user must grant manually)
3. WorkManager as backup (may be delayed up to 15 minutes)

**User Guidance:**
- Show warning: "For reliable timer operation, disable battery optimization for ZCHAT"
- Provide deep link to battery settings: `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

### Exact Alarm Permission (Android 12+)

```kotlin
// Check permission
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    val alarmManager = getSystemService(AlarmManager::class.java)
    if (!alarmManager.canScheduleExactAlarms()) {
        // Request permission
        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
    }
}
```

### Google Play Policy

**Potential Concerns:**
1. Data destruction features may require clear user consent
2. Must not be used for malicious purposes (ransomware-like behavior)

**Mitigations:**
- Clear UI explaining what the feature does
- Require PIN/biometric confirmation before activation
- Show persistent notification while timer is active
- Cannot be triggered remotely without user-configured code

---

## iOS Implementation Notes

### Approach Differences

iOS is MORE restrictive than Android for background execution:

| Feature | Android | iOS |
|---------|---------|-----|
| Exact alarms | Yes (with permission) | NO |
| Background execution | AlarmManager + WorkManager | BGTaskScheduler (limited) |
| Boot completed | Yes | NO (no boot broadcast) |
| App termination survival | Yes (via alarms) | Limited (BGProcessingTask) |

### iOS Implementation Strategy

```swift
// Use BGTaskScheduler with BGProcessingTaskRequest
// Maximum background time: ~30 seconds for refresh, longer for processing

BGTaskScheduler.shared.register(
    forTaskWithIdentifier: "com.zchat.dms.check",
    using: nil
) { task in
    self.handleDmsCheck(task: task as! BGProcessingTask)
}

// Schedule task
let request = BGProcessingTaskRequest(identifier: "com.zchat.dms.check")
request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60) // 15 min
request.requiresNetworkConnectivity = false
request.requiresExternalPower = false

try? BGTaskScheduler.shared.submit(request)
```

**iOS Limitations:**
- Cannot guarantee exact timing
- System may delay background tasks indefinitely
- No survival if device is powered off
- User can disable background app refresh

**iOS Alternative: Push Notification Trigger**
- Use silent push notification as backup trigger
- Requires server infrastructure
- Works even when app is terminated

---

## Security Considerations

### Threat Model

| Threat | Mitigation |
|--------|------------|
| Attacker disables alarm | Timer state stored in encrypted prefs; backup worker checks |
| Attacker changes system time | Store both wall clock AND monotonic time; cross-validate |
| Attacker intercepts cancel code | Code transmitted via Zcash shielded memo (encrypted) |
| Device powered off | Timer resumes on boot; destroy if expired |
| App force-stopped | Timer restored when app next opens or via boot receiver |
| Forensic recovery | Use secure delete; overwrite sensitive data before deletion |

### Time Manipulation Protection

```kotlin
// Store both wall clock and elapsed realtime
data class DmsTimerState(
    val wallClockExpiry: Long,      // System.currentTimeMillis()
    val elapsedRealtimeExpiry: Long, // SystemClock.elapsedRealtime()
    val bootCount: Int               // Settings.Global.BOOT_COUNT
)

// On check, verify both clocks agree (within tolerance)
fun shouldDestroy(): Boolean {
    val wallRemaining = wallClockExpiry - System.currentTimeMillis()
    val elapsedRemaining = elapsedRealtimeExpiry - SystemClock.elapsedRealtime()

    // If clocks diverge significantly, use the shorter remaining time
    // (Assumes attacker tried to delay by changing wall clock)
    return minOf(wallRemaining, elapsedRemaining) <= 0
}
```

### Secure Deletion

```kotlin
// Before deleting files, overwrite with zeros
fun secureDelete(file: File) {
    if (file.exists() && file.isFile) {
        val length = file.length()
        RandomAccessFile(file, "rws").use { raf ->
            raf.seek(0)
            val zeros = ByteArray(4096)
            var remaining = length
            while (remaining > 0) {
                val toWrite = minOf(remaining, zeros.size.toLong()).toInt()
                raf.write(zeros, 0, toWrite)
                remaining -= toWrite
            }
            raf.fd.sync()
        }
        file.delete()
    }
}
```

---

## UI/UX Design

### Timer Activation Flow

```
┌─────────────────────────────────────────┐
│         Dead Man's Switch               │
├─────────────────────────────────────────┤
│                                         │
│  ⏱️ Set Self-Destruct Timer            │
│                                         │
│  Duration: [▼ 2 hours        ]         │
│                                         │
│  Cancel Code: [••••••••]               │
│  (Required to stop timer)               │
│                                         │
│  ☑️ Allow remote cancellation          │
│     via Zcash transaction               │
│                                         │
│  ⚠️ WARNING: All data will be          │
│  permanently destroyed if timer         │
│  expires without cancellation.          │
│                                         │
│  [ Start Timer ]                        │
│                                         │
└─────────────────────────────────────────┘
```

### Active Timer Display

```
┌─────────────────────────────────────────┐
│      ⚠️ SELF-DESTRUCT ACTIVE ⚠️        │
├─────────────────────────────────────────┤
│                                         │
│         Time Remaining:                 │
│                                         │
│           01:45:32                      │
│                                         │
│  ──────────────────────────────         │
│  ████████████░░░░░░░░░░░░░░░░           │
│                                         │
│  [ Cancel with Code ]                   │
│                                         │
│  [ Extend Timer (+1h) ]                 │
│                                         │
└─────────────────────────────────────────┘
```

### Persistent Notification

```
┌─────────────────────────────────────────┐
│ 🔴 ZCHAT - Self-Destruct Active        │
│    1h 45m remaining                     │
│    Tap to cancel                        │
└─────────────────────────────────────────┘
```

---

## Implementation Phases

### Phase 1: Core Timer (MVP)
- [ ] DeadMansSwitchManager class
- [ ] AlarmManager integration
- [ ] Persistent storage in ZchatPreferences
- [ ] Basic UI to start/cancel timer
- [ ] Boot receiver to restore timer

### Phase 2: Reliability Improvements
- [ ] WorkManager backup checker
- [ ] Time manipulation protection
- [ ] Battery optimization guidance
- [ ] Persistent notification

### Phase 3: Remote Cancellation
- [ ] Zcash transaction monitoring for cancel code
- [ ] UI for enabling remote cancellation
- [ ] Documentation for sending cancel from other device

### Phase 4: iOS Port
- [ ] BGTaskScheduler implementation
- [ ] Push notification backup trigger
- [ ] UI parity with Android

---

## Testing Checklist

- [ ] Timer survives app force-stop
- [ ] Timer survives device reboot
- [ ] Timer works in Doze mode
- [ ] Timer works with battery saver enabled
- [ ] Cancellation via local code works
- [ ] Cancellation via remote transaction works
- [ ] Data is completely destroyed on expiry
- [ ] Time manipulation is detected
- [ ] UI shows accurate countdown
- [ ] Notification is persistent and tappable

---

## References

- Android AlarmManager: https://developer.android.com/reference/android/app/AlarmManager
- Android WorkManager: https://developer.android.com/topic/libraries/architecture/workmanager
- Android Doze: https://developer.android.com/training/monitoring-device-state/doze-standby
- iOS BGTaskScheduler: https://developer.apple.com/documentation/backgroundtasks
- Existing DestroyManager: `/home/yourt/zchat-android/ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/util/DestroyManager.kt`
