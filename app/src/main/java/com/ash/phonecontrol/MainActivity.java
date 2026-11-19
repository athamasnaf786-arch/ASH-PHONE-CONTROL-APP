package com.ash.phonecontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements RecognitionListener {

    private static final int REQ_PERMISSIONS = 100;
    private static final String[] PERMISSIONS = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
    };

    private PhoneControl phone;
    private TextView status, heard, result;
    private Button listenBtn;
    private Model model;
    private SpeechService speech;
    private boolean listening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        phone = new PhoneControl(this);
        buildScreen();
        askForPermissions(); // first thing the app does when it opens
    }

    // ---------------------------------------------------------------- screen

    private void buildScreen() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("ASH PHONE CONTROL APP");
        title.setTextColor(Color.CYAN);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(14);
        status.setPadding(0, pad, 0, pad);
        status.setText("Starting...");
        root.addView(status);

        listenBtn = makeButton("START LISTENING", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleListening();
            }
        });
        listenBtn.setEnabled(false);
        root.addView(listenBtn);

        heard = new TextView(this);
        heard.setTextColor(Color.WHITE);
        heard.setTextSize(16);
        heard.setPadding(0, pad, 0, 0);
        root.addView(heard);

        result = new TextView(this);
        result.setTextColor(Color.GREEN);
        result.setTextSize(16);
        result.setPadding(0, pad / 2, 0, pad);
        result.setText("Say: call <name>, whatsapp <name> <message>, flashlight on/off, volume up/down");
        root.addView(result);

        final EditText input = new EditText(this);
        input.setHint("or type a command here");
        input.setHintTextColor(Color.GRAY);
        input.setTextColor(Color.WHITE);
        input.setSingleLine(true);
        root.addView(input);

        root.addView(makeButton("RUN TYPED COMMAND", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runCommand(input.getText().toString());
            }
        }));

        root.addView(makeButton("TURN ON WHATSAPP AUTO-SEND", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        }));

        setContentView(scroll);
    }

    private Button makeButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(listener);
        return b;
    }

    // ----------------------------------------------------------- permissions

    private void askForPermissions() {
        List<String> missing = new ArrayList<>();
        for (String p : PERMISSIONS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            onPermissionsReady();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) onPermissionsReady();
    }

    private void onPermissionsReady() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            loadModel();
        } else {
            status.setText("Microphone permission is needed for voice commands. "
                    + "You can still type commands below.");
        }
        showAccessibilityIntroOnce();
    }

    private void showAccessibilityIntroOnce() {
        SharedPreferences prefs = getSharedPreferences("ash", MODE_PRIVATE);
        if (prefs.getBoolean("intro_done", false) || accessibilityEnabled()) return;
        prefs.edit().putBoolean("intro_done", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Allow phone control")
                .setMessage("To send WhatsApp messages by voice, turn on ASH PHONE CONTROL APP "
                        + "in Accessibility settings. It only works inside WhatsApp and only "
                        + "presses Send after you ask for a message.\n\n"
                        + "If Android says \"Restricted setting\": open Settings > Apps > "
                        + "ASH PHONE CONTROL APP > the 3 dots (top right) > Allow restricted "
                        + "settings, then try again.")
                .setPositiveButton("OPEN SETTINGS", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        openAccessibilitySettings();
                    }
                })
                .setNegativeButton("LATER", null)
                .show();
    }

    private boolean accessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName());
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    // ----------------------------------------------- offline voice (Vosk)

    private void loadModel() {
        if (model != null) return;
        status.setText("Loading the offline voice model... the first time can take a minute.");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(getFilesDir(), "model");
                    File marker = new File(getFilesDir(), "model_ready_v1");
                    if (!marker.exists()) {
                        deleteRecursive(dir);
                        copyAssets("model-en-us", dir);
                        marker.createNewFile();
                    }
                    final Model m = new Model(dir.getAbsolutePath());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            model = m;
                            status.setText("Ready. Tap START LISTENING and say a command.");
                            listenBtn.setEnabled(true);
                        }
                    });
                } catch (final Throwable e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("The voice model could not load: " + e.getMessage()
                                    + "\nYou can still type commands below.");
                        }
                    });
                }
            }
        }).start();
    }

    private void copyAssets(String assetPath, File target) throws IOException {
        AssetManager am = getAssets();
        String[] children = am.list(assetPath);
        if (children != null && children.length > 0) {
            target.mkdirs();
            for (String child : children) {
                copyAssets(assetPath + "/" + child, new File(target, child));
            }
        } else {
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = am.open(assetPath); OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private void toggleListening() {
        if (model == null) return;
        if (listening) {
            stopListening();
            return;
        }
        try {
            Recognizer recognizer = new Recognizer(model, 16000.0f);
            speech = new SpeechService(recognizer, 16000.0f);
            speech.startListening(this);
            listening = true;
            listenBtn.setText("STOP LISTENING");
            status.setText("Listening... say a command.");
        } catch (Exception e) {
            status.setText("Could not start the microphone: " + e.getMessage());
        }
    }

    private void stopListening() {
        if (speech != null) {
            speech.stop();
            speech.shutdown();
            speech = null;
        }
        listening = false;
        listenBtn.setText("START LISTENING");
        status.setText("Stopped.");
    }

    private String extract(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onPartialResult(final String hypothesis) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String t = extract(hypothesis, "partial");
                if (!t.isEmpty()) heard.setText("Hearing: " + t);
            }
        });
    }

    @Override
    public void onResult(final String hypothesis) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String t = extract(hypothesis, "text");
                if (!t.isEmpty()) {
                    heard.setText("Heard: " + t);
                    runCommand(t);
                }
            }
        });
    }

    @Override
    public void onFinalResult(String hypothesis) {
    }

    @Override
    public void onError(final Exception e) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText("Voice error: " + e.getMessage());
            }
        });
    }

    @Override
    public void onTimeout() {
    }

    // -------------------------------------------------------------- commands

    private void runCommand(String text) {
        result.setText(handle(text));
    }

    /** Turns what you said (or typed) into a phone action. */
    private String handle(String raw) {
        String c = raw == null ? "" : raw.trim();
        String l = c.toLowerCase();
        if (l.isEmpty()) return "Say or type a command first";

        if (l.contains("flashlight") || l.contains("flash light") || l.contains("torch")) {
            return phone.flashlight(!l.contains("off"));
        }
        if (l.contains("volume") || l.contains("sound")) {
            if (l.contains("up") || l.contains("increase") || l.contains("raise") || l.contains("louder")) {
                return phone.volumeUp();
            }
            if (l.contains("down") || l.contains("decrease") || l.contains("lower") || l.contains("reduce")) {
                return phone.volumeDown();
            }
        }
        if (l.startsWith("call ")) {
            return phone.call(c.substring(5).trim());
        }
        String[] messagePrefixes = {"whatsapp ", "message ", "text "};
        for (String prefix : messagePrefixes) {
            if (l.startsWith(prefix)) {
                String rest = c.substring(prefix.length()).trim();
                int space = rest.indexOf(' ');
                if (space < 0) return "Say: " + prefix.trim() + " <name> <message>";
                return phone.whatsapp(rest.substring(0, space), rest.substring(space + 1).trim());
            }
        }
        return "I did not understand: " + c;
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onStop() {
        super.onStop();
        if (listening) stopListening(); // release the microphone when the app is hidden
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speech != null) {
            speech.shutdown();
            speech = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
    }
}
