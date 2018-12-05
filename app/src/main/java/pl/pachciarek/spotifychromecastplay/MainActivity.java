package pl.pachciarek.spotifychromecastplay;

import android.content.Context;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.widget.TextView;

import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.Session;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.cookie.Cookie;
import cz.msebera.android.httpclient.entity.StringEntity;
import cz.msebera.android.httpclient.impl.client.BasicCookieStore;
import cz.msebera.android.httpclient.impl.cookie.BasicClientCookie;

public class MainActivity extends AppCompatActivity {
    private TextView logTextView;

    private void log(String text) {
        logTextView.setText(logTextView.getText() + text + "\n");
    }


    private MediaRouter router;
    private Context ctx;
    private CastContext castContext;
    private String accessToken;


    private class SpotifyAccessTokenStealer {
        private BasicCookieStore cs = new BasicCookieStore();
        private AsyncHttpClient cli = new AsyncHttpClient();

        public SpotifyAccessTokenStealer() {
            BasicClientCookie newCookie = new BasicClientCookie("__bon", "MHwwfC0xOTI4Mzc5OTg1fC04MDk5MTk1OTM3MHwxfDF8MXwx");
            newCookie.setDomain(".spotify.com");
            cs.addCookie(newCookie);

            cli.setCookieStore(cs);
        }

        private void parseAndAddCookies(Header[] headers) {
            Pattern cookiePattern = Pattern.compile("^([^=]*)=([^;]*)");

            for (Header header: headers) {
                if (header.getName().equalsIgnoreCase("Set-Cookie")) {
                    Matcher matcher = cookiePattern.matcher(header.getValue());
                    if (matcher.find()) {
                        BasicClientCookie newCookie = new BasicClientCookie(matcher.group(1), matcher.group(2));
                        newCookie.setDomain(".spotify.com");
                        newCookie.setVersion(1);
                        cs.addCookie(newCookie);
                    }
                }
            }
        }

        private String findCookie(String name) {
            for (Cookie cookie: cs.getCookies()) {
                if (cookie.getName().equalsIgnoreCase(name)) {
                    return cookie.getValue();
                }
            }

            return null;
        }

        public void getAccessToken() {
            cli.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 8.1.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.64 Mobile Safari/537.36");

            cli.get("https://accounts.spotify.com/login",
                    new AsyncHttpResponseHandler() {
                        @Override
                        public void onSuccess(int statusCode, Header[] headers, byte[] bytes) {
                            parseAndAddCookies(headers);

                            RequestParams params = new RequestParams();
                            params.put("remember", "false");
                            params.put("captcha_token", "");
                            params.put("csrf_token", findCookie("csrf_token"));
                            params.put("username", "");
                            params.put("password", "");

                            cli.post("https://accounts.spotify.com/api/login",
                                    params,
                                    new AsyncHttpResponseHandler() {
                                        @Override
                                        public void onSuccess(int statusCode, Header[] headers, byte[] bytes) {
                                            parseAndAddCookies(headers);

                                            cli.addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_14_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.67 Safari/537.36");
                                            cli.get("https://open.spotify.com/browse",
                                                    new AsyncHttpResponseHandler() {
                                                        @Override
                                                        public void onSuccess(int statusCode, Header[] headers, byte[] bytes) {
                                                            parseAndAddCookies(headers);
                                                            accessToken = findCookie("wp_access_token");
                                                            castContext.getSessionManager().addSessionManagerListener(mSessionManagerListener);
                                                        }

                                                        @Override
                                                        public void onFailure(int statusCode, Header[] headers, byte[] bytes, Throwable throwable) {
                                                            log("Can't get Spotify player page!");
                                                        }
                                                    });
                                        }

                                        @Override
                                        public void onFailure(int statusCode, Header[] headers, byte[] bytes, Throwable throwable) {
                                            log("Can't login to Spotify!");
                                        }
                                    });
                        }

                        @Override
                        public void onFailure(int statusCode, Header[] headers, byte[] bytes, Throwable throwable) {
                            log("Can't get CSRF token from Spotify!");
                        }
                    });
        }
    }

    private SpotifyAccessTokenStealer tokenStealer = new SpotifyAccessTokenStealer();




    private void startSpotifyCasting() {
        try {
            castContext.getSessionManager().getCurrentCastSession().setMessageReceivedCallbacks("urn:x-cast:com.spotify.chromecast.secure.v1", new Cast.MessageReceivedCallback()
            {
                @Override
                public void onMessageReceived(CastDevice castDevice, String s, String s1)
                {
                    if (s1.equals("{\"type\":\"setCredentialsResponse\"}")) {
                        log("Got Spotify Chromecast app setCredentials response");

                        final AsyncHttpClient client = new AsyncHttpClient();
                        client.addHeader("Authorization", "Bearer " + accessToken);

                        log("Asking WebAPI for devices list");
                        client.get("https://api.spotify.com/v1/me/player/devices", new AsyncHttpResponseHandler() {
                            @Override
                            public void onSuccess(int statusCode, Header[] headers, byte[] bytes) {
                                try {
                                    log("Got Spotify devices list");

                                    JSONObject jsonResponse = new JSONObject(new String(bytes));
                                    JSONArray devices = jsonResponse.getJSONArray("devices");

                                    for (int i = 0; i < devices.length(); i++) {
                                        JSONObject device = devices.getJSONObject(i);
                                        String id = device.getString("id");
                                        String type = device.getString("type");

                                        if (type.equals("CastVideo")) {
                                            log("Found CastVideo device!");

                                            log("Playing music on found device");
                                            StringEntity entity = new StringEntity("{\"device_ids\":[\"" + id + "\"],\"play\":true}");
                                            client.put(ctx,"https://api.spotify.com/v1/me/player",
                                                    entity,
                                                    "application/json",
                                                    new AsyncHttpResponseHandler() {
                                                        @Override
                                                        public void onSuccess(int statusCode, Header[] headers, byte[] bytes) {
                                                            log("Done!");
                                                        }

                                                        @Override
                                                        public void onFailure(int statusCode, Header[] headers, byte[] bytes, Throwable throwable) {
                                                            log("Error in WebAPI play request!");
                                                        }
                                                    });
                                            break;
                                        }
                                    }
                                } catch (Exception e) {
                                    log("Probably JSON parse error!");
                                }
                            }

                            @Override
                            public void onFailure(int statusCode, Header[] headers, byte[] bytes, Throwable throwable) {
                                log("Error in WebAPI devices request!");
                            }
                        });
                    }
                }
            });

            log("Sending token to Spotify Chromecast app");
            castContext.getSessionManager().getCurrentCastSession().sendMessage("urn:x-cast:com.spotify.chromecast.secure.v1", "{\"requestId\":0,\"type\":\"setCredentials\",\"credentials\":\"" + accessToken + "\",\"expiresIn\":864000}");
        } catch (Exception e) {

        }
    }

    private final SessionManagerListener mSessionManagerListener = new SessionManagerListenerImpl();

    private class SessionManagerListenerImpl implements SessionManagerListener {
        @Override
        public void onSessionStarted(Session session, String sessionId) {
            log("Starting cast session");
            startSpotifyCasting();
        }

        @Override
        public void onSessionResumed(Session session, boolean wasSuspended) {
            log("Resuming cast session");
            startSpotifyCasting();
        }

        @Override
        public void onSessionEnded(Session session, int error) { }
        @Override
        public void onSessionSuspended(Session session, int error) { }
        @Override
        public void onSessionStarting(Session session) { }
        @Override
        public void onSessionResuming(Session session, String sessionId) { }
        @Override
        public void onSessionStartFailed(Session session, int error) { }
        @Override
        public void onSessionResumeFailed(Session session, int error) { }
        @Override
        public void onSessionEnding(Session session) { }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        logTextView = findViewById(R.id.logTextView);

        ctx = this.getApplicationContext();
        router = MediaRouter.getInstance(ctx);
        castContext = CastContext.getSharedInstance(ctx);

        log("CastContext initialized");

        tokenStealer.getAccessToken();
    }

    MediaRouter.Callback callback = new MediaRouter.Callback() {
        @Override
        public void onRouteAdded(MediaRouter router, MediaRouter.RouteInfo route) {
            log("Proper route added to MediaRouter: " + route.getName());
            router.selectRoute(route);
        }

        @Override
        public void onRouteChanged(MediaRouter router, MediaRouter.RouteInfo route) {
            log("Proper route changed in MediaRouter: " + route.getName());
            router.selectRoute(route);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();

        log("Checking existing routes");

        List<MediaRouter.RouteInfo> routes = router.getRoutes();

        MediaRouteSelector selector = new MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast("CC32E753"))
                .build();

        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).matchesSelector(selector)) {
                log("Found proper route: " + routes.get(i).getName());
                router.selectRoute(routes.get(i));
                return;
            }
        }

        log("No proper route found");

        log("Adding MediaRouter callback");
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_FORCE_DISCOVERY);
    }

    @Override
    protected void onStop() {
        log("Removing MediaRouter callback");
        router.removeCallback(callback);
        super.onStop();
    }
}
