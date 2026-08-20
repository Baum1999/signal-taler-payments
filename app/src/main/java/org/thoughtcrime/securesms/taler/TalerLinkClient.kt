package org.thoughtcrime.securesms.taler

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.taler.wallet.link.ConnectionState
import net.taler.wallet.link.ITalerLink
import net.taler.wallet.link.OperationStatusResult
import net.taler.wallet.link.PaymentPreviewResult
import net.taler.wallet.link.PrepareSendRequest
import net.taler.wallet.link.PrepareSendResult
import net.taler.wallet.link.TalerOperationStatus
import net.taler.wallet.link.TalerUriValidity
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Ergebnis eines Aufrufs an die lokale Taler-Schnittstelle. Die ersten drei
 * Faelle sind Signal-lokale Zustaende (kein AIDL-Aufruf noetig oder er ist
 * fehlgeschlagen) - siehe docs/API.md Abschnitt 2.4/2.5.
 */
sealed interface TalerLinkResult<out T> {
  data object NichtInstalliert : TalerLinkResult<Nothing>
  data object NichtVertrauenswuerdig : TalerLinkResult<Nothing>
  data object KeinConsent : TalerLinkResult<Nothing>
  data object Fehler : TalerLinkResult<Nothing>
  data class Ergebnis<T>(val value: T) : TalerLinkResult<T>
}

private class BindFailedException(message: String) : Exception(message)

/**
 * Client fuer die lokale AIDL-Schnittstelle von "GNU Taler fuer Signal".
 * Prueft vor jedem Bind das Signing-Cert des aufgeloesten Packages gegen
 * [TalerAllowlist] - siehe docs/API.md Abschnitt 2.1/2.2. Kein
 * `protectionLevel="signature"`, da beide Apps absichtlich mit
 * unterschiedlichen Keystores signiert sind; die Absicherung sitzt komplett
 * hier im Code.
 */
class TalerLinkClient(private val context: Context) {

  suspend fun getConnectionState(): TalerLinkResult<ConnectionState> =
    call { it.getConnectionState().state }

  suspend fun validateUri(uri: String): TalerLinkResult<TalerUriValidity> =
    call { it.validateUri(uri).validity }

  suspend fun previewForUri(uri: String): TalerLinkResult<PaymentPreviewResult> =
    call { it.previewForUri(uri) }

  suspend fun statusForUri(uri: String): TalerLinkResult<TalerOperationStatus> =
    call { it.statusForUri(uri).status }

  suspend fun prepareSend(request: PrepareSendRequest): TalerLinkResult<PrepareSendResult> =
    call { it.prepareSend(request) }

  private suspend fun <T> call(block: (ITalerLink) -> T): TalerLinkResult<T> {
    if (!isTalerInstalled()) return TalerLinkResult.NichtInstalliert
    if (!isTalerTrusted()) return TalerLinkResult.NichtVertrauenswuerdig

    var connection: ServiceConnection? = null
    return try {
      val service = suspendCancellableCoroutine<ITalerLink> { cont ->
        val conn = object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            if (cont.isActive) cont.resume(ITalerLink.Stub.asInterface(binder))
          }

          override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        connection = conn
        val intent = Intent(TalerAllowlist.BIND_ACTION).setPackage(TalerAllowlist.PACKAGE)
        if (!context.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
          cont.resumeWithException(BindFailedException("bindService(${TalerAllowlist.BIND_ACTION}) fehlgeschlagen"))
        }
        cont.invokeOnCancellation { runCatching { context.unbindService(conn) } }
      }
      val result = withContext(Dispatchers.IO) { block(service) }
      TalerLinkResult.Ergebnis(result)
    } catch (e: SecurityException) {
      // Cert-Allowlist-Pruefung auf Taler-Seite fehlgeschlagen - siehe
      // docs/API.md Abschnitt 2.5. Sollte praktisch nie eintreten, da Signal
      // vor dem Bind bereits selbst prueft (isTalerTrusted).
      TalerLinkResult.NichtVertrauenswuerdig
    } catch (e: CancellationException) {
      // CancellationException ist auf JVM-Ebene eine Unterklasse von
      // IllegalStateException. Ohne diesen frueheren, spezifischeren Catch
      // wuerde eine echte Coroutine-Cancellation (z.B. via Job.cancel()) vom
      // catch(IllegalStateException) darunter mitgefangen und zu einem
      // irrefuehrenden KeinConsent-Ergebnis verfaelscht, statt wie erforderlich
      // weiterzupropagieren. Bitte NICHT "vereinfachen"/entfernen - ein Aufrufer
      // (AttachmentKeyboardFragment's Stale-Closure-Race-Fix) verlaesst sich
      // darauf, dass Cancellation hier tatsaechlich wirkt.
      throw e
    } catch (e: IllegalStateException) {
      // Aufrufer ist in der Allowlist, aber der Nutzer hat die Verbindung
      // (noch) nicht bestaetigt - docs/API.md Abschnitt 2.5/2.6.
      TalerLinkResult.KeinConsent
    } catch (e: Exception) {
      TalerLinkResult.Fehler
    } finally {
      connection?.let { runCatching { context.unbindService(it) } }
    }
  }

  private fun isTalerInstalled(): Boolean = try {
    context.packageManager.getPackageInfo(TalerAllowlist.PACKAGE, 0)
    true
  } catch (e: PackageManager.NameNotFoundException) {
    false
  }

  private fun isTalerTrusted(): Boolean {
    val actual = try {
      signingCertSha256(context.packageManager, TalerAllowlist.PACKAGE)
    } catch (e: PackageManager.NameNotFoundException) {
      null
    } ?: return false
    return actual.equals(TalerAllowlist.CERT_SHA256, ignoreCase = true)
  }
}
