package app.morphe.extension.syncforreddit.diagnostics;

import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Sync's own logging, which R8 emptied in the release build, sent to logcat under one tag,
 * with every request Sync makes and what it was answered.
 *
 * <p>A diagnostic: off unless its patch is included, and meant to be removed once it has
 * answered what it was built to.
 *
 * @noinspection unused
 */
public final class DiagnosticLogging {
    /** What to filter logcat by: {@code adb logcat -s SyncDiag}. */
    private static final String TAG = "SyncDiag";

    /** Query parameters that carry a key or token, which are not written out. */
    private static final String[] SECRET = {"key", "token", "api_key", "access_token", "sig"};

    private DiagnosticLogging() {}

    /** Answered by the patch: true when it is included. */
    public static boolean isPatchIncluded() {
        return false;
    }

    /** Sync's {@code Logger}, for a message and the tag it was given. */
    public static void message(String tag, String message) {
        if (isPatchIncluded()) {
            Log.i(TAG, "[" + tag + "] " + message);
        }
    }

    /** Sync's {@code Logger}, for an exception it caught and carried on from. */
    public static void exception(Exception caught) {
        if (isPatchIncluded() && caught != null) {
            Log.w(TAG, "caught", caught);
        }
    }

    /** What Sync would have sent its crash reporting, with the level it gave. */
    public static void report(int level, String tag, String message) {
        if (isPatchIncluded()) {
            Log.println(level >= Log.VERBOSE && level <= Log.ASSERT ? level : Log.INFO, TAG,
                    "{" + tag + "} " + message);
        }
    }

    /** Placed on the network, so it sees each request as it actually leaves and returns. */
    public static final Interceptor HTTP = chain -> {
        Request request = chain.request();
        long started = System.nanoTime();
        String which = request.method() + " " + redact(request.url().toString());
        try {
            Response response = chain.proceed(request);
            long took = (System.nanoTime() - started) / 1_000_000;
            int code = response.code();
            String line = code + " " + which + " (" + took + " ms)";
            if (code >= 400) {
                Log.w(TAG, "http " + line);
            } else {
                Log.i(TAG, "http " + line);
            }
            return response;
        } catch (IOException ex) {
            Log.w(TAG, "http failed " + which + ": " + ex);
            throw ex;
        }
    };

    private static String redact(String link) {
        try {
            Uri uri = Uri.parse(link);
            if (uri.getQuery() == null) {
                return link;
            }
            Uri.Builder clean = uri.buildUpon().clearQuery();
            for (String name : uri.getQueryParameterNames()) {
                boolean secret = false;
                for (String s : SECRET) {
                    if (name.toLowerCase(Locale.ROOT).contains(s)) {
                        secret = true;
                        break;
                    }
                }
                for (String value : uri.getQueryParameters(name)) {
                    clean.appendQueryParameter(name, secret ? "…" : value);
                }
            }
            return clean.build().toString();
        } catch (Exception ex) {
            return link;
        }
    }
}
