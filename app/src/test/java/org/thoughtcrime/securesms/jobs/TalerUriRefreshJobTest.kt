/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import android.app.Application
import net.taler.wallet.link.TalerHttpsLink
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.jobmanager.JsonJobData

/**
 * Bug B1 (docs/nach-taler-abgabe/bugs.md): `taler://` ist der gemeinsame
 * Schluessel eines Vorgangs. Ein Job, der mit dem DD-103-Link aus einem
 * Body angelegt wird, fragt deshalb unter `taler://` ab, nutzt dieselbe
 * Dedup-Queue wie ein Job fuer `taler://` und schreibt `taler://` in die
 * Bestaetigungsnachricht.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class TalerUriRefreshJobTest {

  private val uri = "taler://pay-push/exchange.demo.taler.net/jobA"

  @Test
  fun constructor_keepsAnHttpsLinkAsTalerUri() {
    val job = TalerUriRefreshJob(TalerHttpsLink.wrap(uri))

    assertEquals(uri, JsonJobData.deserialize(job.serialize()).getString("uri"))
  }

  @Test
  fun constructor_usesTheSameQueueForHttpsLinkAndTalerUri() {
    val fromLink = TalerUriRefreshJob(TalerHttpsLink.wrap(uri))
    val fromUri = TalerUriRefreshJob(uri)

    assertEquals(fromUri.parameters.queue, fromLink.parameters.queue)
  }
}
