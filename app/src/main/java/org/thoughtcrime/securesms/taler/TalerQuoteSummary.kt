package org.thoughtcrime.securesms.taler

import android.content.Context
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.database.SignalDatabase

/**
 * Kurze, sichere Zusammenfassung einer Taler-Zahlungsnachricht fuer die
 * Zitat-Anzeige (QuoteView.resolveBody) - ersetzt die rohe(n) URI(s) (inkl.
 * Claim-/Pay-Token) im Zitat, sowohl in der Compose-Vorschau als auch in der
 * Chat-Zitat-Blase. Bewusst ohne eigenen QuoteModel.Type/Wire-Format-Eintrag
 * (einfachere, aus dem Plan empfohlene Variante: kein Backup-/Proto-Eingriff
 * noetig) - QuoteView ruft dies bei jedem Rendern frisch auf, genau wie
 * TalerMenuState es fuer das Long-Press-Menue tut.
 */
object TalerQuoteSummary {

  /** null, wenn [body] keine Taler-URI traegt - dann zeigt QuoteView den Body unveraendert. */
  fun buildOrNull(context: Context, body: String): String? {
    val uris = urisFromMessageBody(body)
    if (uris.isEmpty()) return null

    if (uris.size > 1) {
      return context.getString(R.string.TalerFork_quote_group_summary, uris.size)
    }

    val record = SignalDatabase.talerPayments.getByUri(uris.single())
    val amount = record?.amount?.let { amount -> "${amount.replace(".", ",")} ${record.currency.orEmpty()}".trim() }
    val status = record?.let { TalerPaymentCardPresenter.plainStatusLabel(context, it.status) }
      ?: context.getString(R.string.TalerFork_details_status_pending)

    return listOfNotNull(amount, status).joinToString(" · ")
  }
}
