package app.morphe.extension.syncforreddit.backup;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import app.morphe.extension.shared.Logger;
import com.laurencedawson.reddit_sync.provider.RedditProvider;

/**
 * Puts the signed in accounts, and the settings each one keeps for itself, into Sync's backup,
 * and takes them back out of it on a restore.
 *
 * <p>Sync keeps its accounts in a table of its database, not among its settings, so its backup,
 * which writes out the settings and nothing else, never held them. It also writes only the
 * settings in use at the time, so the settings of every other account were left behind too.
 *
 * <p>An account is kept by its refresh token, which signs it in for as long as it is not revoked.
 * That makes the section a set of passwords, so it is only added when asked for, and is
 * encrypted with a passphrase given at the time. Sync's own settings beside it are left as they
 * were.
 *
 * @noinspection unused
 */
public final class BackupAccountsPatch {
    /** The section of the backup that is ours. */
    static final String SECTION = "sync_up_accounts";

    /** The account Sync keeps for being signed out, which is not an account to back up. */
    private static final String SIGNED_OUT = "Logged out";

    private static final String NAME = "account_name";
    private static final String ID = "account_id";

    /** What marks a settings file as an account's own, rather than one never written. */
    private static final String OWN_SETTINGS = "set";

    private static final int FORMAT = 1;
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** How many times a passphrase can be got wrong on a restore before the accounts are left. */
    private static final int ATTEMPTS = 3;

    /**
     * Whether the next backup has been asked about, so that it goes ahead without asking again.
     * Set when the dialog is answered, and spent by the backup it was answered for.
     */
    private static boolean answered;

    /** The passphrase the next backup encrypts the accounts with, or null to leave them out. */
    private static char[] passphrase;

    private BackupAccountsPatch() {}

    // region Leaving our section to us.

    /**
     * @return Whether a setting is ours, which Sync's restore should then not write.
     */
    public static boolean isOurs(String key) {
        return SECTION.equals(key);
    }

    // endregion

    // region Backing up.

    /**
     * Called as a backup begins, on the main thread.
     *
     * @param screen    The backup screen, whose method {@code write} writes the backup.
     * @param write     The name of that method, which is obfuscated.
     * @return Whether the backup should wait for an answer, in which case it will be started
     * again once there is one.
     */
    public static boolean askFirst(Context context, Object screen, String write) {
        try {
            if (answered) {
                answered = false;
                return false;
            }
            passphrase = null;
            if (context == null || countAccounts(context) == 0) {
                return false;
            }
            ask(context, () -> {
                answered = true;
                try {
                    Method writing = screen.getClass().getDeclaredMethod(write);
                    writing.setAccessible(true);
                    writing.invoke(screen);
                } catch (Exception ex) {
                    answered = false;
                    passphrase = null;
                    Logger.printInfo(() -> "Could not start the backup again: " + ex);
                }
            });
            return true;
        } catch (Exception ex) {
            // Backing up the settings alone is the app as it was.
            Logger.printInfo(() -> "Could not ask about the accounts: " + ex);
            return false;
        }
    }

    private static void ask(Context context, Runnable then) {
        float density = context.getResources().getDisplayMetrics().density;
        int edge = Math.round(24 * density);

        LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(edge, edge / 2, edge, 0);

        TextView explanation = new TextView(context);
        explanation.setText("The backup can hold your accounts and each one's settings, so a "
                + "restore signs them all back in. They are encrypted with a passphrase you "
                + "will need to restore them.");
        fields.addView(explanation);

        EditText first = passphraseField(context, "Passphrase");
        EditText second = passphraseField(context, "Passphrase again");
        fields.addView(first);
        fields.addView(second);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Include accounts?")
                .setView(fields)
                .setPositiveButton("Include accounts", null)
                .setNegativeButton("Settings only", (shown, which) -> {
                    passphrase = null;
                    then.run();
                })
                .setNeutralButton("Cancel", null)
                .create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String typed = first.getText().toString();
                    if (typed.isEmpty()) {
                        first.setError("Choose a passphrase");
                        return;
                    }
                    if (!typed.equals(second.getText().toString())) {
                        second.setError("The passphrases are different");
                        return;
                    }
                    passphrase = typed.toCharArray();
                    dialog.dismiss();
                    then.run();
                }));
        dialog.show();
    }

    private static EditText passphraseField(Context context, String hint) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    /**
     * Called with the backup before it is written, holding every setting it will.
     */
    public static void addAccounts(JSONObject backup) {
        char[] secret = passphrase;
        passphrase = null;
        if (secret == null || backup == null) {
            return;
        }
        try {
            Context context = app.morphe.extension.shared.Utils.getContext();
            JSONObject accounts = readAccounts(context);
            backup.put(SECTION, encrypt(accounts.toString(), secret));
            int count = accounts.getJSONArray("accounts").length();
            Logger.printInfo(() -> "Backed up " + count + " accounts");
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not back up the accounts: " + ex);
            say("Accounts were not backed up");
        } finally {
            java.util.Arrays.fill(secret, '\0');
        }
    }

    private static int countAccounts(Context context) {
        try (Cursor rows = context.getContentResolver()
                .query(RedditProvider.S, new String[]{NAME}, null, null, null)) {
            int count = 0;
            while (rows != null && rows.moveToNext()) {
                String name = rows.getString(0);
                if (name != null && !SIGNED_OUT.equalsIgnoreCase(name)) {
                    count++;
                }
            }
            return count;
        }
    }

    /** Every account, as its row of the table and the settings it keeps for itself. */
    private static JSONObject readAccounts(Context context) throws Exception {
        JSONArray accounts = new JSONArray();
        try (Cursor rows = context.getContentResolver()
                .query(RedditProvider.S, null, null, null, null)) {
            while (rows != null && rows.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int column = 0; column < rows.getColumnCount(); column++) {
                    String name = rows.getColumnName(column);
                    if (ID.equals(name) || rows.isNull(column)) {
                        continue;
                    }
                    row.put(name, rows.getString(column));
                }
                String name = row.optString(NAME, null);
                if (name == null || SIGNED_OUT.equalsIgnoreCase(name)) {
                    continue;
                }

                JSONObject account = new JSONObject();
                account.put("row", row);
                SharedPreferences own = context.getSharedPreferences(
                        name.toLowerCase(Locale.ENGLISH), Context.MODE_PRIVATE);
                if (own.contains(OWN_SETTINGS)) {
                    account.put("settings", writeSettings(own));
                }
                accounts.put(account);
            }
        }
        return new JSONObject().put("version", FORMAT).put("accounts", accounts);
    }

    /** Settings with their types, which JSON alone would lose between an int and a long. */
    private static JSONObject writeSettings(SharedPreferences settings) throws Exception {
        JSONObject written = new JSONObject();
        for (Map.Entry<String, ?> entry : settings.getAll().entrySet()) {
            Object value = entry.getValue();
            String type;
            Object held = value;
            if (value instanceof Boolean) type = "b";
            else if (value instanceof Integer) type = "i";
            else if (value instanceof Long) type = "l";
            else if (value instanceof Float) type = "f";
            else if (value instanceof String) type = "s";
            else if (value instanceof Set) {
                type = "set";
                JSONArray items = new JSONArray();
                for (Object item : (Set<?>) value) items.put(String.valueOf(item));
                held = items;
            } else continue;
            written.put(entry.getKey(), new JSONObject().put("t", type).put("v", held));
        }
        return written;
    }

    // endregion

    // region Restoring.

    /**
     * Called on the restore's own thread, once Sync has written its settings back.
     *
     * @param context The screen's activity, for asking the passphrase, or null if it has gone.
     */
    public static void restoreAccounts(Context context, JSONObject backup) {
        try {
            if (backup == null || !backup.has(SECTION)) {
                return;
            }
            if (context == null || Looper.myLooper() == Looper.getMainLooper()) {
                // There is nowhere to ask, or asking would wait on the thread that has to show
                // the question.
                Logger.printInfo(() -> "Cannot ask for the passphrase, accounts left out");
                return;
            }
            JSONObject sealed = backup.getJSONObject(SECTION);

            String opened = null;
            String problem = null;
            for (int attempt = 0; attempt < ATTEMPTS && opened == null; attempt++) {
                char[] typed = askForPassphrase(context, problem);
                if (typed == null) {
                    Logger.printInfo(() -> "Restoring the accounts was skipped");
                    return;
                }
                try {
                    opened = decrypt(sealed, typed);
                } catch (javax.crypto.AEADBadTagException ex) {
                    problem = "That passphrase is not the one the backup was made with.";
                } finally {
                    java.util.Arrays.fill(typed, '\0');
                }
            }
            if (opened == null) {
                say("Accounts were not restored");
                return;
            }

            int[] counts = writeAccounts(context, new JSONObject(opened));
            say(counts[0] == 0
                    ? "Every account was already signed in"
                    : "Restored " + counts[0] + (counts[0] == 1 ? " account" : " accounts")
                    + (counts[1] > 0 ? ", " + counts[1] + " already signed in" : "")
                    + ". Restart Sync to switch to them.");
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not restore the accounts: " + ex);
            say("Accounts were not restored");
        }
    }

    /** Asks on the main thread and waits for the answer here, or null if it was declined. */
    private static char[] askForPassphrase(Context context, String problem)
            throws InterruptedException {
        CountDownLatch answeredIt = new CountDownLatch(1);
        AtomicReference<char[]> typed = new AtomicReference<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                EditText field = passphraseField(context, "Passphrase");
                LinearLayout around = new LinearLayout(context);
                int edge = Math.round(24 * context.getResources().getDisplayMetrics().density);
                around.setPadding(edge, edge / 2, edge, 0);
                around.addView(field, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                new AlertDialog.Builder(context)
                        .setTitle("Restore accounts?")
                        .setMessage(problem != null ? problem
                                : "This backup holds accounts. Enter the passphrase it was "
                                + "made with to sign them back in.")
                        .setView(around)
                        .setPositiveButton("Restore accounts", (shown, which) ->
                                typed.set(field.getText().toString().toCharArray()))
                        .setNegativeButton("Skip", null)
                        .setOnDismissListener(shown -> answeredIt.countDown())
                        .setCancelable(false)
                        .show();
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not ask for the passphrase: " + ex);
                answeredIt.countDown();
            }
        });
        answeredIt.await();
        return typed.get();
    }

    /**
     * Adds every account not already signed in, and writes back the settings of each.
     *
     * @return How many were added, and how many were already there.
     */
    private static int[] writeAccounts(Context context, JSONObject restored) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Set<String> present = new HashSet<>();
        try (Cursor rows = resolver.query(RedditProvider.S, new String[]{NAME}, null, null,
                null)) {
            while (rows != null && rows.moveToNext()) {
                String name = rows.getString(0);
                if (name != null) present.add(name.toLowerCase(Locale.ENGLISH));
            }
        }

        int added = 0;
        int there = 0;
        JSONArray accounts = restored.getJSONArray("accounts");
        for (int index = 0; index < accounts.length(); index++) {
            JSONObject account = accounts.getJSONObject(index);
            JSONObject row = account.getJSONObject("row");
            String name = row.getString(NAME);

            if (present.contains(name.toLowerCase(Locale.ENGLISH))) {
                there++;
            } else {
                ContentValues values = new ContentValues();
                Iterator<String> columns = row.keys();
                while (columns.hasNext()) {
                    String column = columns.next();
                    // The id is the table's own, and one of the same number may already be
                    // another account, which inserting with it would replace.
                    if (!ID.equals(column)) values.put(column, row.getString(column));
                }
                resolver.insert(RedditProvider.S, values);
                added++;
            }

            JSONObject settings = account.optJSONObject("settings");
            if (settings != null) {
                readSettings(context.getSharedPreferences(
                        name.toLowerCase(Locale.ENGLISH), Context.MODE_PRIVATE), settings);
            }
        }
        resolver.notifyChange(RedditProvider.S, null);
        return new int[]{added, there};
    }

    private static void readSettings(SharedPreferences into, JSONObject settings)
            throws Exception {
        SharedPreferences.Editor editing = into.edit().clear();
        Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = settings.getJSONObject(key);
            switch (entry.getString("t")) {
                case "b": editing.putBoolean(key, entry.getBoolean("v")); break;
                case "i": editing.putInt(key, entry.getInt("v")); break;
                case "l": editing.putLong(key, entry.getLong("v")); break;
                case "f": editing.putFloat(key, (float) entry.getDouble("v")); break;
                case "s": editing.putString(key, entry.getString("v")); break;
                case "set": {
                    JSONArray items = entry.getJSONArray("v");
                    Set<String> set = new HashSet<>();
                    for (int i = 0; i < items.length(); i++) set.add(items.getString(i));
                    editing.putStringSet(key, set);
                    break;
                }
                default: break;
            }
        }
        editing.commit();
    }

    // endregion

    // region Encryption.

    private static JSONObject encrypt(String plain, char[] secret) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(salt);
        random.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(secret, salt, ITERATIONS),
                new GCMParameterSpec(TAG_BITS, iv));
        byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

        return new JSONObject()
                .put("version", FORMAT)
                .put("kdf", KDF)
                .put("iterations", ITERATIONS)
                .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .put("data", Base64.encodeToString(sealed, Base64.NO_WRAP));
    }

    private static String decrypt(JSONObject sealed, char[] secret) throws Exception {
        byte[] salt = Base64.decode(sealed.getString("salt"), Base64.NO_WRAP);
        byte[] iv = Base64.decode(sealed.getString("iv"), Base64.NO_WRAP);
        byte[] data = Base64.decode(sealed.getString("data"), Base64.NO_WRAP);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(secret, salt, sealed.getInt("iterations")),
                new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
    }

    private static SecretKey key(char[] secret, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, KEY_BITS);
        try {
            byte[] derived = SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
            return new SecretKeySpec(derived, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    // endregion

    /** A short word on what happened, shown wherever Sync is. */
    private static void say(String what) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(app.morphe.extension.shared.Utils.getContext(), what,
                        Toast.LENGTH_LONG).show();
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not say: " + what);
            }
        });
    }
}
