package org.thoughtcrime.securesms.taler

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.core.view.drawToBitmap
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.Util
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.io.ByteArrayOutputStream

/**
 * Klick-Handler fuer die Taler-Aktionen im Long-Press-Menue/Mehrfachauswahl-
 * Toolbar (Ablehnen/Abbrechen/Aktualisieren/Rueckerstatten/Kopieren-als-Bild) -
 * jede Funktion liest den Zustand ueber [TalerMenuState.compute] beim Klick
 * frisch neu, statt sich auf den beim Menue-Aufbau berechneten Snapshot zu
 * verlassen (dasselbe Race-Handling-Muster wie
 * TalerAcceptRejectActions.onRejectClicked). Bei einer Gruppenkarte mit
 * mehreren gleichzeitig zutreffenden eigenen Anteilen wird die zugrunde
 * liegende Aktion pro URI einzeln aufgerufen.
 */
object TalerMenuActions {

  private const val SNAPSHOT_WIDTH_PX = 1080

  fun onRejectFromMenu(context: Context, messageRecord: MessageRecord) {
    val uri = TalerMenuState.compute(context, messageRecord).rejectUri ?: return
    TalerAcceptRejectActions.onRejectClicked(context, uri, messageRecord.threadId)
  }

  fun onCancelFromMenu(context: Context, messageRecord: MessageRecord) {
    TalerMenuState.compute(context, messageRecord).cancelUris.forEach { uri ->
      TalerAcceptRejectActions.onCancelClicked(context, uri, messageRecord.threadId)
    }
  }

  fun onRefreshFromMenu(context: Context, messageRecord: MessageRecord) {
    TalerMenuState.compute(context, messageRecord).cancelUris.forEach { uri ->
      TalerAcceptRejectActions.onRefreshClicked(context, uri, messageRecord.threadId)
    }
  }

  fun onRefundFromMenu(context: Context, messageRecord: MessageRecord) {
    TalerMenuState.compute(context, messageRecord).refundUris.forEach { uri ->
      TalerRefundActions.onRefundClicked(
        context,
        uri,
        messageRecord.threadId,
        quoteMessageId = messageRecord.id,
        quoteAuthor = messageRecord.fromRecipient.id,
      )
    }
  }

  /**
   * "Kopieren als Bild": rendert die Karte offscreen zu einem PNG - dieselbe
   * Pipeline wie TalerForwardGate.attachPaymentSnapshot ("Weiterleiten als
   * Bild") - und legt das Ergebnis statt in MultiShareArgs direkt in die
   * Zwischenablage. BlobContentProvider (exported=false,
   * grantUriPermissions=true) erlaubt das ohne weitere Manifest-Aenderung.
   */
  fun onCopyAsImageFromMenu(context: Context, lifecycleOwner: LifecycleOwner, messageRecord: MessageRecord) {
    val uris = urisFromMessageBody(messageRecord.body)
    if (uris.isEmpty()) return

    lifecycleOwner.lifecycleScope.launch {
      val pngUri = withContext(Dispatchers.Default) {
        val view = if (uris.size > 1) {
          TalerPaymentCardPresenter.renderStandaloneGroup(
            context = context,
            uris = uris,
            threadId = messageRecord.threadId,
            sender = messageRecord.fromRecipient,
            messageBody = messageRecord.body,
            messageId = messageRecord.id,
            messageExtrasTalerPayment = messageRecord.messageExtras?.talerPayment,
          )
        } else {
          val record = SignalDatabase.talerPayments.getByUri(uris.single())
          TalerPaymentCardPresenter.renderStandalone(context, record)
        }
        view.measure(
          View.MeasureSpec.makeMeasureSpec(SNAPSHOT_WIDTH_PX, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val bitmap = view.drawToBitmap()
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 0, outputStream)

        AppDependencies.blobs
          .forData(outputStream.toByteArray())
          .withMimeType("image/png")
          .withFileName("Taler-Zahlung.png")
          .createForSingleSessionInMemory()
      }

      val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      clipboardManager?.setPrimaryClip(ClipData.newUri(context.contentResolver, "Taler-Zahlung", pngUri))
    }
  }

  /** "Kopieren als Transkript": funktional identisch zum bisherigen "Kopieren". */
  fun onCopyAsTextFromMenu(context: Context, messageRecord: MessageRecord) {
    Util.copyToClipboard(context, messageRecord.body)
  }
}
