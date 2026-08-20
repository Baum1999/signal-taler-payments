package org.thoughtcrime.securesms.taler

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.taler.wallet.link.ConnectionState
import org.signal.core.ui.compose.Rows
import org.thoughtcrime.securesms.R

/**
 * Verbindungstest-Zeile in den App-Einstellungen (Schritt 3, docs/API.md) -
 * ausgelagert aus AppSettingsFragment.kt (REVIEW.md H4-artig, gleiche
 * Begruendung wie beim ViewHolder: Eingriff in eine Upstream-Datei klein
 * halten). Wird durch das volle "Verbinden"-UX aus dem Fallback-Konzept
 * (docs/API.md) ersetzt, sobald das volle Zustandsmodell steht - bis dahin
 * bleibt es als Nachweis der Kopplung.
 */
@Composable
fun TalerConnectionTestRow() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  fun describe(status: TalerLinkResult<ConnectionState>): String = when (status) {
    is TalerLinkResult.NichtInstalliert -> context.getString(R.string.TalerFork_status_not_installed)
    is TalerLinkResult.NichtVertrauenswuerdig -> context.getString(R.string.TalerFork_status_untrusted)
    is TalerLinkResult.KeinConsent -> context.getString(R.string.TalerFork_status_not_connected)
    is TalerLinkResult.Fehler -> context.getString(R.string.TalerFork_settings_connection_error)
    is TalerLinkResult.Ergebnis -> when (status.value) {
      ConnectionState.VERBUNDEN -> context.getString(R.string.TalerFork_settings_connected)
      ConnectionState.NICHT_VERBUNDEN -> context.getString(R.string.TalerFork_status_not_connected)
    }
  }

  // Loest Talers einmaligen Consent-Dialog per expliziter
  // startActivityForResult aus (nur so ist callingPackage dort verlaesslich
  // gesetzt, siehe ConsentActivity in taler-android).
  val consentLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) {
    scope.launch {
      val message = try {
        describe(TalerLinkClient(context).getConnectionState())
      } catch (e: Exception) {
        if (e is CancellationException) throw e
        context.getString(R.string.TalerFork_settings_connection_error_detail, e.javaClass.simpleName)
      }
      Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
  }

  Rows.TextRow(
    text = stringResource(R.string.TalerFork_settings_row_label),
    icon = painterResource(R.drawable.symbol_payment_24),
    onClick = {
      scope.launch {
        val status = try {
          TalerLinkClient(context).getConnectionState()
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          val message = context.getString(R.string.TalerFork_settings_connection_error_detail, e.javaClass.simpleName)
          Toast.makeText(context, message, Toast.LENGTH_LONG).show()
          return@launch
        }
        if (status is TalerLinkResult.Ergebnis && status.value == ConnectionState.NICHT_VERBUNDEN) {
          consentLauncher.launch(
            Intent().apply {
              setClassName(TalerAllowlist.PACKAGE, "net.taler.wallet.link.ConsentActivity")
              setPackage(TalerAllowlist.PACKAGE)
            }
          )
        } else {
          Toast.makeText(context, describe(status), Toast.LENGTH_LONG).show()
        }
      }
    }
  )
}
