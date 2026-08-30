/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.pm.ActivityInfo
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PlayerObserver(
  private val activity: PlayerActivity,
) : MPVLib.EventObserver,
  KoinComponent {
  private val playerPreferences: PlayerPreferences by inject()

  private fun shouldIgnoreCallback(): Boolean =
    activity.player.isExiting || !activity.isActivePlaybackOwner()

  private fun isVideoGeometryProperty(property: String): Boolean =
    property == "video-params/aspect" ||
      property == "video-params/w" ||
      property == "video-params/h"

  private fun shouldBypassUiThread(property: String): Boolean =
    isVideoGeometryProperty(property) ||
      property == "container-fps"

  /**
   * Stretch deliberately keeps a positive video-aspect-override so the picture fills the current
   * viewport. PlayerActivity's normal "Video" orientation refresh ignores positive overrides to
   * avoid treating custom aspect ratios as source geometry, which unintentionally also excluded
   * Stretch. Read the source video geometry from MPVView instead and only restore auto-orientation
   * for the built-in Stretch mode; custom aspect ratios remain untouched.
   */
  private fun requestStretchVideoOrientationUpdate(property: String? = null) {
    if (property != null && !isVideoGeometryProperty(property)) return

    activity.runOnUiThread {
      if (shouldIgnoreCallback() || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
      if (playerPreferences.orientation.get() != PlayerOrientation.Video) return@runOnUiThread
      if (playerPreferences.lastCustomAspectRatio.get() > 0f) return@runOnUiThread
      if (playerPreferences.lastVideoAspect.get() != VideoAspect.Stretch) return@runOnUiThread

      val sourceAspect =
        runCatching { activity.player.getVideoOutAspect() }
          .getOrNull()
          ?.takeIf { it > 0.0 }
          ?: return@runOnUiThread

      val targetOrientation =
        if (sourceAspect > 1.0) {
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

      if (activity.requestedOrientation != targetOrientation) {
        activity.requestedOrientation = targetOrientation
      }
    }
  }

  /**
   * A raw eof-reached=true property edge is not enough to decide playlist navigation for remote
   * media: a failed/stopped stream can briefly expose that flag too. Drive auto-next only from the
   * matching END_FILE event when libmpv explicitly reports reason=eof.
   *
   * Do not compare time-pos with duration here. WebDAV/proxy playback can deliver a slightly stale
   * last position at a legitimate EOF; rejecting that EOF leaves the session ended and paused with
   * no next-item request, which is worse than the original bug.
   */
  private fun isNaturalEndFile(data: MPVNode): Boolean {
    val reasonNode = data["reason"] ?: return false
    val textReason = runCatching { reasonNode.asString() }.getOrNull()
    if (textReason != null) return textReason.equals("eof", ignoreCase = true)
    return runCatching { reasonNode.asInt()?.toInt() == END_FILE_REASON_EOF }.getOrDefault(false)
  }

  override fun eventProperty(property: String) {
    if (shouldIgnoreCallback()) return
    activity.runOnUiThread {
      if (!shouldIgnoreCallback()) activity.onObserverEvent(property)
    }
  }

  override fun eventProperty(
    property: String,
    value: Long,
  ) {
    if (shouldIgnoreCallback()) return
    if (shouldBypassUiThread(property)) {
      activity.runIfActivePlaybackOwner { activity.onObserverEvent(property, value) }
      requestStretchVideoOrientationUpdate(property)
    } else {
      activity.runOnUiThread {
        if (!shouldIgnoreCallback()) activity.onObserverEvent(property, value)
      }
    }
  }

  override fun eventProperty(
    property: String,
    value: Boolean,
  ) {
    if (shouldIgnoreCallback()) return
    // Keep false flowing so PlayerActivity can clear a completed/cancelled EOF transition. Only
    // the unqualified true edge is suppressed; a validated true is emitted from END_FILE below.
    if (property == "eof-reached" && value) return
    activity.runOnUiThread {
      if (!shouldIgnoreCallback()) activity.onObserverEvent(property, value)
    }
  }

  override fun eventProperty(
    property: String,
    value: String,
  ) {
    if (shouldIgnoreCallback()) return
    activity.runOnUiThread {
      if (!shouldIgnoreCallback()) activity.onObserverEvent(property, value)
    }
  }

  override fun eventProperty(
    property: String,
    value: Double,
  ) {
    if (shouldIgnoreCallback()) return
    if (shouldBypassUiThread(property)) {
      activity.runIfActivePlaybackOwner { activity.onObserverEvent(property, value) }
      requestStretchVideoOrientationUpdate(property)
    } else {
      activity.runOnUiThread {
        if (!shouldIgnoreCallback()) activity.onObserverEvent(property, value)
      }
    }
  }

  @Suppress("EmptyFunctionBlock")
  override fun eventProperty(
    property: String,
    value: MPVNode,
  ) {
    if (shouldIgnoreCallback()) return
    activity.runOnUiThread {
      if (!shouldIgnoreCallback()) activity.onObserverEvent(property, value)
    }
  }

  override fun event(
    eventId: Int,
    data: MPVNode,
  ) {
    if (shouldIgnoreCallback()) return
    val naturalEnd = eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE && isNaturalEndFile(data)
    activity.runOnUiThread {
      if (shouldIgnoreCallback()) return@runOnUiThread
      activity.event(eventId)
      if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
        requestStretchVideoOrientationUpdate()
      }
      if (naturalEnd) {
        activity.onObserverEvent("eof-reached", true)
      }
    }
  }

  private companion object {
    const val END_FILE_REASON_EOF = 0
  }
}
