package app.morphe.extension.syncforreddit.ui.links;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;

/**
 * Plays a YouTube link in Sync, in YouTube's own embedded player, in place of Sync's player.
 *
 * <p>Sync's player is built on the YouTube Android Player API, which the YouTube app no longer
 * serves, so it failed to start and offered to install YouTube. The embed is what a web page
 * plays YouTube with, and is still served.
 *
 * <p>The embed refuses to play for a page that does not say where it is (error 153), and a page
 * a WebView is handed as text says nothing unless it is given an address of its own. The page
 * is therefore given one, made from the app's package, and the same address is handed to the
 * player as the origin that it and the page talk to each other through.
 *
 * <p>Where the embed cannot play the video, because the uploader has turned embedding off, the
 * video is gone, or the link is not one the embed can play, the link is handed to the YouTube
 * app as before.
 *
 * @noinspection unused
 */
public final class YouTubePlayerPatch {
    /** What Sync passes the link to its player under. */
    private static final String LINK = "url";

    /** A video's id: eleven characters of letters, digits, dashes and underscores. */
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    /** A playlist's id, which is longer and of the same characters. */
    private static final Pattern PLAYLIST_ID = Pattern.compile("^[A-Za-z0-9_-]{2,64}$");

    private YouTubePlayerPatch() {}

    /**
     * Called as Sync's player is created, after the activity has been.
     *
     * @return Whether the link was taken care of here, so Sync's own player should not start.
     */
    public static boolean play(Activity activity) {
        String link = null;
        try {
            Intent started = activity.getIntent();
            link = started == null ? null : started.getStringExtra(LINK);
            if (TextUtils.isEmpty(link)) {
                return false;
            }
            link = link.trim();

            Uri uri = Uri.parse(link);
            String video = videoIdOf(link);
            String playlist = uri.isHierarchical() ? uri.getQueryParameter("list") : null;
            if (playlist != null && !PLAYLIST_ID.matcher(playlist).matches()) {
                playlist = null;
            }
            if (video == null && playlist == null) {
                // A channel, a channel's live page, a clip: nothing the embed can be told to play.
                return handOff(activity, link);
            }

            new Player(activity, link, video, playlist, startOf(link),
                    uri.getPath() != null && uri.getPath().startsWith("/shorts/")).show();
            Logger.printInfo(() -> "Playing " + activity.getIntent().getStringExtra(LINK)
                    + " in Sync");
            return true;
        } catch (Throwable ex) {
            String failed = link;
            Logger.printInfo(() -> "Could not play " + failed + " in Sync: " + ex);
            return failed != null && handOff(activity, failed);
        }
    }

    /** The video's id, read the way Sync's thumbnails read it, or null if there is none. */
    private static String videoIdOf(String link) {
        String id = YouTubeIdPatch.idFromPath(link);
        if (id == null) {
            try {
                id = y7.a.v(link);
            } catch (Throwable ex) {
                Logger.printInfo(() -> "Could not read the video's id: " + ex);
                id = null;
            }
        }
        return id != null && VIDEO_ID.matcher(id).matches() ? id : null;
    }

    private static int startOf(String link) {
        try {
            return Math.max(0, y7.a.w(link));
        } catch (Throwable ex) {
            return 0;
        }
    }

    /**
     * Sends the link to the YouTube app, or whatever else opens YouTube links, and closes the
     * player.
     *
     * @return Whether anything took it.
     */
    static boolean handOff(Activity activity, String link) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            activity.startActivity(view);
            activity.finish();
            activity.overridePendingTransition(0, 0);
            Logger.printInfo(() -> "Handed " + link + " to another app");
            return true;
        } catch (ActivityNotFoundException ex) {
            Logger.printInfo(() -> "Nothing else opens YouTube links: " + ex);
            return false;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not hand off the YouTube link: " + ex);
            return false;
        }
    }

    /** The player, which belongs to one activity and goes when it does. */
    public static final class Player implements Application.ActivityLifecycleCallbacks {
        private final Activity activity;
        private final String link;
        private final String video;
        private final String playlist;
        private final int start;
        private final boolean upright;

        private FrameLayout root;
        private WebView web;
        /** What the embed is showing full screen, while it is. */
        private View fullScreen;
        private WebChromeClient.CustomViewCallback leaveFullScreen;
        private boolean handedOff;

        Player(Activity activity, String link, String video, String playlist, int start,
               boolean upright) {
            this.activity = activity;
            this.link = link;
            this.video = video;
            this.playlist = playlist;
            this.start = start;
            this.upright = upright;
        }

        @SuppressLint("SetJavaScriptEnabled")
        void show() {
            if (activity.getActionBar() != null) {
                activity.getActionBar().hide();
            }
            activity.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.BLACK));

            root = new FrameLayout(activity);
            root.setBackgroundColor(Color.BLACK);

            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(bar(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            web = new WebView(activity);
            web.setBackgroundColor(Color.BLACK);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            web.addJavascriptInterface(new Bridge(), "SyncUp");
            web.setWebChromeClient(new FullScreen());
            web.setOnKeyListener(this::onKey);
            column.addView(web, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            root.addView(column, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.setFocusableInTouchMode(true);
            root.setOnKeyListener(this::onKey);
            activity.setContentView(root);
            root.requestFocus();

            activity.getApplication().registerActivityLifecycleCallbacks(this);

            String origin = originOf(activity);
            web.loadDataWithBaseURL(origin + "/", page(origin), "text/html", "utf-8", null);
        }

        /** Close, and open in YouTube, along the top. */
        private View bar() {
            LinearLayout bar = new LinearLayout(activity);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            int edge = dp(12);
            bar.setPadding(edge, edge, edge, edge);

            TextView close = button("✕");
            close.setContentDescription("Close");
            close.setOnClickListener(v -> activity.finish());
            bar.addView(close);

            View gap = new View(activity);
            bar.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));

            TextView open = button("Open in YouTube");
            open.setOnClickListener(v -> {
                handedOff = true;
                handOff(activity, link);
            });
            bar.addView(open);
            return bar;
        }

        private TextView button(String text) {
            TextView button = new TextView(activity);
            button.setText(text);
            button.setTextColor(Color.WHITE);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            int edge = dp(10);
            button.setPadding(edge, edge / 2, edge, edge / 2);
            TypedValue ripple = new TypedValue();
            if (activity.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, ripple, true)) {
                button.setBackgroundResource(ripple.resourceId);
            }
            return button;
        }

        private int dp(int value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }

        /**
         * The page the embed is loaded in. It fills the screen, keeps the player's shape in the
         * middle of it, and closes the player when the black around it is tapped.
         */
        private String page(String origin) {
            String ratio = upright ? "9 / 16" : "16 / 9";
            StringBuilder vars = new StringBuilder()
                    .append("autoplay:1,playsinline:1,rel:0,fs:1")
                    .append(",origin:'").append(origin).append("'")
                    .append(",widget_referrer:'").append(origin).append("'");
            if (start > 0) {
                vars.append(",start:").append(start);
            }
            if (playlist != null) {
                vars.append(",list:'").append(playlist).append("'");
                if (video == null) {
                    vars.append(",listType:'playlist'");
                }
            }
            String videoId = video == null ? "" : "videoId:'" + video + "',";

            return "<!DOCTYPE html><html><head>"
                    + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                    + "<meta name='referrer' content='strict-origin-when-cross-origin'>"
                    + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
                    + "#frame{position:absolute;inset:0;margin:auto;aspect-ratio:" + ratio + ";"
                    + "width:min(100vw,calc(100vh * " + ratio + "));max-height:100vh}"
                    + "#player{width:100%;height:100%}</style></head>"
                    + "<body><div id='frame'><div id='player'></div></div><script>"
                    + "document.body.addEventListener('click',function(e){"
                    + "if(!document.getElementById('frame').contains(e.target))SyncUp.close();});"
                    + "var api=document.createElement('script');"
                    + "api.src='https://www.youtube.com/iframe_api';"
                    + "api.onerror=function(){SyncUp.failed(-1);};"
                    + "document.head.appendChild(api);"
                    + "function onYouTubeIframeAPIReady(){new YT.Player('player',{"
                    + "width:'100%',height:'100%'," + videoId
                    + "playerVars:{" + vars + "},"
                    + "events:{onReady:function(e){e.target.playVideo();},"
                    + "onError:function(e){SyncUp.failed(e.data);}}});}"
                    + "</script></body></html>";
        }

        /** Back leaves full screen first, and closes the player after that. */
        private boolean onKey(View view, int code, KeyEvent event) {
            if (code != KeyEvent.KEYCODE_BACK || fullScreen == null) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && leaveFullScreen != null) {
                leaveFullScreen.onCustomViewHidden();
            }
            return true;
        }

        /** What the page can tell the app. Public, since the WebView reaches it by reflection. */
        public final class Bridge {
            @JavascriptInterface
            public void failed(int code) {
                Logger.printInfo(() -> "The embed could not play " + link + " (" + code
                        + "), handing it to YouTube");
                activity.runOnUiThread(() -> {
                    if (!handedOff && !activity.isFinishing()) {
                        handedOff = true;
                        handOff(activity, link);
                    }
                });
            }

            @JavascriptInterface
            public void close() {
                activity.runOnUiThread(activity::finish);
            }
        }

        /** The embed's own full screen button, which a WebView has to make room for. */
        private final class FullScreen extends WebChromeClient {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullScreen != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullScreen = view;
                leaveFullScreen = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                view.setFocusableInTouchMode(true);
                view.setOnKeyListener(Player.this::onKey);
                view.requestFocus();
                systemBars(false);
                if (!upright) {
                    activity.setRequestedOrientation(
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                }
            }

            @Override
            public void onHideCustomView() {
                if (fullScreen == null) {
                    return;
                }
                root.removeView(fullScreen);
                fullScreen = null;
                leaveFullScreen = null;
                systemBars(true);
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                root.requestFocus();
            }
        }

        @SuppressWarnings("deprecation")
        private void systemBars(boolean shown) {
            View decor = activity.getWindow().getDecorView();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController bars = decor.getWindowInsetsController();
                if (bars == null) {
                    return;
                }
                if (shown) {
                    bars.show(WindowInsets.Type.systemBars());
                } else {
                    bars.hide(WindowInsets.Type.systemBars());
                    bars.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                decor.setSystemUiVisibility(shown ? 0
                        : View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        }

        // region The activity's lifecycle, which is Sync's to override and not ours.

        @Override
        public void onActivityPaused(Activity paused) {
            if (paused == activity && web != null) {
                web.onPause();
            }
        }

        @Override
        public void onActivityResumed(Activity resumed) {
            if (resumed == activity && web != null) {
                web.onResume();
            }
        }

        @Override
        public void onActivityDestroyed(Activity destroyed) {
            if (destroyed != activity) {
                return;
            }
            activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            if (web != null) {
                // Stops the sound, which a WebView otherwise carries on playing until collected.
                web.loadUrl("about:blank");
                web.stopLoading();
                ViewGroup parent = (ViewGroup) web.getParent();
                if (parent != null) {
                    parent.removeView(web);
                }
                web.destroy();
                web = null;
            }
        }

        @Override
        public void onActivityCreated(Activity created, Bundle state) {}

        @Override
        public void onActivityStarted(Activity started) {}

        @Override
        public void onActivityStopped(Activity stopped) {}

        @Override
        public void onActivitySaveInstanceState(Activity saving, Bundle state) {}

        // endregion
    }

    /**
     * The address the page is given, made from the app's package. Underscores are allowed in a
     * package and not in a host name, so they are made dashes.
     */
    private static String originOf(Activity activity) {
        return "https://" + activity.getPackageName().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
