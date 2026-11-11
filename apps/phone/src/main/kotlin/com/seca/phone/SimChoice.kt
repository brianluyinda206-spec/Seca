package com.seca.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.seca.core.contacts.PhoneNumbers
import com.seca.core.design.SecaIcons
import com.seca.core.design.component.segmentShape

/** A SIM able to place calls. [slot] counts from 1, when Android tells it. */
internal data class Sim(val handle: PhoneAccountHandle, val label: String, val slot: Int?) {
    val name: String get() = if (slot != null) "SIM $slot · $label" else label
}

/** The SIMs able to place calls, in slot order. Empty without the phone-state permission, which comes with being the phone app. */
internal fun callingSims(context: Context): List<Sim> {
    if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val telecom = context.getSystemService(TelecomManager::class.java) ?: return emptyList()
    val telephony = context.getSystemService(TelephonyManager::class.java)
    val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    return runCatching {
        telecom.callCapablePhoneAccounts.map { handle ->
            val label = telecom.getPhoneAccount(handle)?.label?.toString()?.takeIf { it.isNotBlank() } ?: "SIM"
            val slot = runCatching {
                val subscription = telephony?.getSubscriptionId(handle) ?: return@runCatching null
                subscriptions?.getActiveSubscriptionInfo(subscription)?.simSlotIndex?.plus(1)
            }.getOrNull()
            Sim(handle, label, slot)
        }.sortedBy { it.slot ?: Int.MAX_VALUE }
    }.getOrDefault(emptyList())
}

/**
 * Compatibility shim for callers from older versions. SIM choices are no longer
 * persisted: previous saved choices are cleared and reads/writes are ignored.
 */
internal class SimPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("seca_sim_choice", Context.MODE_PRIVATE)

    init {
        prefs.edit { clear() }
    }

    operator fun get(key: String): PhoneAccountHandle? = null

    operator fun set(key: String, handle: PhoneAccountHandle) = Unit

    fun forget(key: String) = Unit
}

/** A contact is identified by its lookup key; a number without a contact by its normalized number. */
internal fun simKeyOf(lookupKey: String?, number: String, numbers: PhoneNumbers): String =
    if (lookupKey.isNullOrEmpty()) "number:${numbers.key(number)}" else "contact:$lookupKey"

/** The lookup key and name of the contact [number] belongs to; null for someone not in the contacts. */
internal fun contactFor(context: Context, number: String): Pair<String, String>? = runCatching {
    context.contentResolver.query(
        Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
        arrayOf(PhoneLookup.LOOKUP_KEY, PhoneLookup.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() to c.getString(1).orEmpty() else null }
}.getOrNull()

/** Before a call: the SIM to place it with, or the choice to offer. */
internal sealed interface SimRoute {
    /** [account] null lets Android pick, as with a single SIM or an emergency call. */
    data class Direct(val account: PhoneAccountHandle?) : SimRoute

    data class Ask(
        val number: String,
        val sims: List<Sim>,
        val key: String,
        val contactName: String?,
        /** Retained for compatibility; no choice is remembered, including on one-off calls. */
        val once: Boolean = false,
        /** Retained for compatibility; there is no remembered usual SIM. */
        val usual: PhoneAccountHandle? = null,
    ) : SimRoute
}

/**
 * Every non-emergency call on a multi-SIM phone asks which SIM to use. The answer
 * applies to this call only and is never saved. Emergency numbers bypass the chooser.
 */
internal fun routeCall(context: Context, number: String): SimRoute {
    val sims = callingSims(context)
    if (sims.size < 2 || isEmergency(context, number)) return SimRoute.Direct(null)
    val contact = contactFor(context, number)
    val key = simKeyOf(contact?.first, number, PhoneNumbers(PhoneNumbers.detectRegion(context)))
    return SimRoute.Ask(number, sims, key, contact?.second?.ifEmpty { null })
}

/** The long-press call action follows the same ask-every-time, never-save behavior. */
internal fun routeCallOnce(context: Context, number: String): SimRoute {
    val route = routeCall(context, number)
    return when (route) {
        is SimRoute.Direct -> route
        is SimRoute.Ask -> route.copy(once = true)
    }
}

private fun isEmergency(context: Context, number: String): Boolean =
    runCatching { context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(number) == true }.getOrDefault(true)

/** A per-call SIM chooser. It deliberately has no remember option or usual-SIM indicator. */
@Composable
internal fun SimChooserDialog(
    sims: List<Sim>,
    title: String,
    @Suppress("UNUSED_PARAMETER") rememberLabel: String?,
    onDismiss: () -> Unit,
    onPick: (sim: Sim, remember: Boolean) -> Unit,
    @Suppress("UNUSED_PARAMETER") usual: PhoneAccountHandle? = null,
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(SecaIcons.Phone, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                sims.forEachIndexed { index, sim ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(segmentShape(index, sims.size, outer = 20.dp))
                            .background(colors.surfaceContainerHighest)
                            .clickable(onClickLabel = sim.name) { onPick(sim, false) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(colors.primaryContainer),
                        ) {
                            Text(
                                text = sim.slot?.toString() ?: sim.label.take(1),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onPrimaryContainer,
                            )
                        }
                        Text(
                            text = sim.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Per-contact SIM preferences are disabled; multi-SIM calls always ask at call time. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun SimPreferenceRow(number: String, lookupKey: String?, name: String, numbers: PhoneNumbers) {
    // Intentionally empty: there is no per-contact SIM setting to save or display.
    LocalContext.current
}
