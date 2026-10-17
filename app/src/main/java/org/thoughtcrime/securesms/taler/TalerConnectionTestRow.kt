package org.thoughtcrime.securesms.taler

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.signal.core.ui.compose.Rows
import org.thoughtcrime.securesms.R

/**
 * Zeigt in den App-Einstellungen an, ob die Taler-Wallet auf diesem Geraet
 * vorhanden ist - ausgelagert aus AppSettingsFragment.kt (REVIEW.md H4-artig,
 * gleiche Begruendung wie beim ViewHolder: Eingriff in eine Upstream-Datei
 * klein halten).
 *
 * Frueher ein "Verbindungstest": solange Signal den Zahlungszustand nur ueber
 * die Taler-App erfahren konnte, gab es eine Verbindung, die man aufbauen,
 * testen und verweigern konnte. Die gibt es nicht mehr - Signal fragt den
 * Exchange selbst (siehe [TalerPeerContractResolver]), und von der Wallet
 * haengt nur noch ab, ob der Nutzer eine Zahlung ausloesen kann. Entsprechend
 * ist das hier eine reine Zustandsanzeige ohne Aktion.
 */
@Composable
fun TalerConnectionTestRow() {
  val context = LocalContext.current

  val statusRes = when {
    !TalerInstallation.isInstalled(context) -> R.string.TalerFork_settings_wallet_not_installed
    !TalerInstallation.isTrusted(context) -> R.string.TalerFork_settings_wallet_untrusted
    else -> R.string.TalerFork_settings_wallet_installed
  }

  Rows.TextRow(
    text = stringResource(R.string.TalerFork_settings_row_label),
    label = stringResource(statusRes),
    icon = painterResource(R.drawable.symbol_payment_24)
  )
}
