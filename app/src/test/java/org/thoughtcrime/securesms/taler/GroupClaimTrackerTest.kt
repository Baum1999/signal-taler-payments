package org.thoughtcrime.securesms.taler

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory fake von [SharedPreferences] - gleiches Muster wie
 * SentRefundStoreTest.kt (taler-android), vermeidet Robolectric fuer diesen
 * reinen Key-Vorhandensein-Speicher. Nur put/contains/apply werden von
 * GroupClaimTracker gebraucht, alles andere wirft laut, statt still
 * No-op zu machen, falls doch mal mehr gebraucht wird.
 */
private class FakeSharedPreferences : SharedPreferences {
  private val values = mutableSetOf<String>()

  override fun contains(key: String): Boolean = values.contains(key)

  override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
    private val pendingAdds = mutableSetOf<String>()
    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
      if (value) pendingAdds.add(key)
      return this
    }
    override fun apply() {
      values.addAll(pendingAdds)
    }
    override fun commit(): Boolean {
      apply()
      return true
    }
    override fun remove(key: String) = throw NotImplementedError()
    override fun clear() = throw NotImplementedError()
    override fun putString(key: String, value: String?) = throw NotImplementedError()
    override fun putInt(key: String, value: Int) = throw NotImplementedError()
    override fun putLong(key: String, value: Long) = throw NotImplementedError()
    override fun putFloat(key: String, value: Float) = throw NotImplementedError()
    override fun putStringSet(key: String, values: MutableSet<String>?) = throw NotImplementedError()
  }

  override fun getAll() = throw NotImplementedError()
  override fun getString(key: String, defValue: String?) = throw NotImplementedError()
  override fun getInt(key: String, defValue: Int) = throw NotImplementedError()
  override fun getLong(key: String, defValue: Long) = throw NotImplementedError()
  override fun getFloat(key: String, defValue: Float) = throw NotImplementedError()
  override fun getBoolean(key: String, defValue: Boolean) = throw NotImplementedError()
  override fun getStringSet(key: String, defValues: MutableSet<String>?) = throw NotImplementedError()
  override fun registerOnSharedPreferenceChangeListener(
    listener: SharedPreferences.OnSharedPreferenceChangeListener,
  ) = throw NotImplementedError()
  override fun unregisterOnSharedPreferenceChangeListener(
    listener: SharedPreferences.OnSharedPreferenceChangeListener,
  ) = throw NotImplementedError()
}

class GroupClaimTrackerTest {

  private fun tracker() = GroupClaimTracker(FakeSharedPreferences())

  @Test
  fun `hasClaimedAny is false when nothing was ever tracked`() {
    assertFalse(tracker().hasClaimedAny(listOf("u0", "u1")))
  }

  @Test
  fun `tracking a uri makes hasClaimedAny true for a list containing it`() {
    val sut = tracker()
    sut.track("u1")
    assertTrue(sut.hasClaimedAny(listOf("u0", "u1", "u2")))
  }

  @Test
  fun `hasClaimedAny is false when the tracked uri is not in the given list`() {
    val sut = tracker()
    sut.track("u-other-message")
    assertFalse(sut.hasClaimedAny(listOf("u0", "u1")))
  }

  @Test
  fun `tracking twice does not throw and still reports claimed`() {
    val sut = tracker()
    sut.track("u1")
    sut.track("u1")
    assertTrue(sut.hasClaimedAny(listOf("u1")))
  }
}
