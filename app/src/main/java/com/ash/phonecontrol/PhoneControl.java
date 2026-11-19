package com.ash.phonecontrol;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.ContactsContract;

/** Phone actions. Everything here runs on the phone itself. */
public class PhoneControl {

    // Change this to your country code (91 = India)
    private static final String DEFAULT_COUNTRY_CODE = "91";

    private final Context ctx;
    private final AudioManager audio;
    private final CameraManager camera;

    public PhoneControl(Context context) {
        ctx = context.getApplicationContext();
        audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        camera = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    private boolean has(String permission) {
        return ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isNumber(String s) {
        return s != null && s.trim().matches("[0-9+()\\-\\s]+");
    }

    private String findNumber(String name) {
        if (name == null || !has(Manifest.permission.READ_CONTACTS)) return null;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + name.trim() + "%"},
                    null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    public String volumeUp() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
        return "Volume up";
    }

    public String volumeDown() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        return "Volume down";
    }

    public String flashlight(boolean on) {
        try {
            for (String id : camera.getCameraIdList()) {
                Boolean hasFlash = camera.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (hasFlash != null && hasFlash) {
                    camera.setTorchMode(id, on);
                    return on ? "Flashlight on" : "Flashlight off";
                }
            }
            return "No flashlight found";
        } catch (Exception e) {
            return "Flashlight error";
        }
    }

    /** target = contact name (like "mother") or a phone number */
    public String call(String target) {
        if (!has(Manifest.permission.CALL_PHONE)) return "Call permission is missing";
        String number = isNumber(target) ? target : findNumber(target);
        if (number == null) return "I could not find " + target + " in your contacts";
        try {
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return "Calling " + target;
        } catch (Exception e) {
            return "Could not start the call";
        }
    }

    /** Opens the WhatsApp chat with the message typed. The accessibility service presses Send. */
    public String whatsapp(String target, String message) {
        String number = isNumber(target) ? target : findNumber(target);
        if (number == null) return "I could not find " + target + " in your contacts";
        String digits = number.replaceAll("[^0-9]", "");
        if (digits.length() == 10) digits = DEFAULT_COUNTRY_CODE + digits;
        try {
            String text = message == null ? "" : message;
            Uri uri = Uri.parse("https://wa.me/" + digits + "?text=" + Uri.encode(text));
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.setPackage("com.whatsapp");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Allow the accessibility service to press Send for the next 20 seconds
            AshAccessibilityService.sendUntil = System.currentTimeMillis() + 20000;
            ctx.startActivity(i);
            if (AshAccessibilityService.connected) {
                return "Sending WhatsApp message to " + target;
            }
            return "WhatsApp chat opened with " + target
                    + ". Turn on Accessibility for this app to send automatically.";
        } catch (Exception e) {
            AshAccessibilityService.sendUntil = 0;
            return "WhatsApp is not available";
        }
    }
}
