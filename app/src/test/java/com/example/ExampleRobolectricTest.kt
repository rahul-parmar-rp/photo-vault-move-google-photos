package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppPreferences
import com.example.engine.MediaMoverEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("PhotoVault Auto-Move", appName)
  }

  @Test
  fun `media mover detects valid media extensions`() {
    assertTrue(MediaMoverEngine.isMediaFile(File("/sdcard/DCIM/Camera/IMG_20261009_0850.jpg")))
    assertTrue(MediaMoverEngine.isMediaFile(File("/sdcard/Pictures/Screenshots/Screenshot_1.png")))
    assertTrue(MediaMoverEngine.isMediaFile(File("/sdcard/DCIM/Camera/VID_20261009.mp4")))
    assertFalse(MediaMoverEngine.isMediaFile(File("/sdcard/DCIM/Camera/.nomedia")))
    assertFalse(MediaMoverEngine.isMediaFile(File("/sdcard/DCIM/Camera/file.txt")))
    assertFalse(MediaMoverEngine.isMediaFile(File("/sdcard/DCIM/Camera/.pending_capture.jpg")))
  }

  @Test
  fun `default target directory is inside storage`() {
    val target = AppPreferences.getDefaultTargetDirectory()
    assertTrue(target.contains("_PhotoVault_Local"))
  }

  @Test
  fun `segregated vault subfolder resolution matches specifications`() {
    val base = File("/sdcard/_PhotoVault_Local")
    val cameraSub = MediaMoverEngine.resolveVaultSubfolder(base, File("/sdcard/DCIM/Camera/IMG_01.jpg"))
    assertEquals("Vault_Camera", cameraSub.name)

    val screenshotSub = MediaMoverEngine.resolveVaultSubfolder(base, File("/sdcard/Pictures/Screenshots/SS_01.png"))
    assertEquals("Vault_Screenshots", screenshotSub.name)

    val downloadSub = MediaMoverEngine.resolveVaultSubfolder(base, File("/sdcard/Download/pic.jpg"))
    assertEquals("Vault_Downloads", downloadSub.name)

    val whatsAppSub = MediaMoverEngine.resolveVaultSubfolder(base, File("/sdcard/Pictures/WhatsApp/IMG.jpg"))
    assertEquals("Vault_WhatsApp", whatsAppSub.name)
  }

  @Test
  fun `restore folder resolution returns original camera or screenshots path`() {
    val cameraFile = File("/sdcard/_PhotoVault_Local/Vault_Camera/IMG_01.jpg")
    val restoredCamera = MediaMoverEngine.resolveOriginalFolder(cameraFile)
    assertTrue(restoredCamera.absolutePath.contains("DCIM/Camera"))

    val ssFile = File("/sdcard/_PhotoVault_Local/Vault_Screenshots/SS_01.png")
    val restoredSS = MediaMoverEngine.resolveOriginalFolder(ssFile)
    assertTrue(restoredSS.absolutePath.contains("Screenshots"))
  }
}
