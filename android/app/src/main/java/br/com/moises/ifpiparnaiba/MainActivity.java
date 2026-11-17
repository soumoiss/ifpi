package br.com.moises.ifpiparnaiba;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Navegador WebView interno. Nunca despacha HTTP/HTTPS para o navegador externo. */
public final class MainActivity extends Activity {
    private static final String HOME = "https://soumoiss.github.io/ifpi/";
    private static final int PAPER = Color.rgb(238, 231, 215);
    private static final int GREEN = Color.rgb(20, 83, 45);
    private static final int REQUEST_FILES = 110;
    private static final int REQUEST_MEDIA = 111;
    private static final int REQUEST_LOCATION = 112;
    private static final int REQUEST_CAMERA_FOR_FILES = 113;
    private static final int REQUEST_STORAGE = 114;
    private static final long MAX_BLOB_BYTES = 16L * 1024 * 1024;

    private FrameLayout root;
    private WebView mainView;
    private ProgressBar progress;
    private LinearLayout failurePanel;
    private TextView failureText;
    private final ArrayList<Popup> popups = new ArrayList<>();
    private final Map<WebView, TextView> popupHosts = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ValueCallbackHolder picker;
    private WebChromeClient.FileChooserParams pendingPickerParams;
    private Uri photoUri;
    private Uri videoUri;
    private File photoFile;
    private File videoFile;
    private PermissionRequest waitingMedia;
    private String[] waitingMediaResources;
    private GeolocationPermissions.Callback waitingGeo;
    private String waitingGeoOrigin;
    private DownloadJob waitingDownload;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    private int dp(float x) { return Math.round(getResources().getDisplayMetrics().density * x); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        CookieManager.getInstance().setAcceptCookie(true);

        root = new FrameLayout(this);
        root.setBackgroundColor(PAPER);
        setContentView(root);
        // Android 15/16 impõe edge-to-edge: manter a WebView fora das áreas das barras.
        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                if (fullscreenView != null) root.setPadding(0, 0, 0, 0);
                else root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }

        mainView = createWebView();
        root.addView(mainView, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(47, 158, 65)));
        FrameLayout.LayoutParams progressLp = new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP);
        root.addView(progress, progressLp);
        progress.setVisibility(View.GONE);

        createFailurePanel();
        if (state == null || mainView.restoreState(state) == null) {
            mainView.loadUrl(HOME);
        }
    }

    private WebView createWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(PAPER);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        view.setWebViewClient(new InternalClient());
        view.setWebChromeClient(new InternalChrome());
        view.setDownloadListener((url, userAgent, disposition, mime, length) ->
                onDownload(view, url, userAgent, disposition, mime));
        view.addJavascriptInterface(new BlobBridge(), "AndroidBlobBridge");
        return view;
    }

    private void createFailurePanel() {
        failurePanel = new LinearLayout(this);
        failurePanel.setOrientation(LinearLayout.VERTICAL);
        failurePanel.setGravity(Gravity.CENTER);
        failurePanel.setPadding(dp(28), dp(24), dp(28), dp(24));
        failurePanel.setBackgroundColor(PAPER);
        TextView heading = new TextView(this);
        heading.setText("IFPI Parnaíba");
        heading.setTextSize(25);
        heading.setTextColor(GREEN);
        heading.setGravity(Gravity.CENTER);
        failurePanel.addView(heading);
        failureText = new TextView(this);
        failureText.setText("Não foi possível carregar a página. Confira sua conexão.");
        failureText.setTextSize(15);
        failureText.setTextColor(Color.rgb(46, 62, 54));
        failureText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(18);
        failurePanel.addView(failureText, p);
        Button retry = new Button(this);
        retry.setText("Tentar novamente");
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> {
            failurePanel.setVisibility(View.GONE);
            WebView current = activeWebView();
            String url = current.getUrl();
            current.loadUrl(url == null || url.isEmpty() ? HOME : url);
        });
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(-2, -2);
        retryLp.topMargin = dp(20);
        failurePanel.addView(retry, retryLp);
        root.addView(failurePanel, new FrameLayout.LayoutParams(-1, -1));
        failurePanel.setVisibility(View.GONE);
    }

    private WebView activeWebView() {
        return popups.isEmpty() ? mainView : popups.get(popups.size() - 1).web;
    }

    private void showFailure(WebView view, String message) {
        if (view != activeWebView()) return;
        failureText.setText(message);
        failurePanel.setVisibility(View.VISIBLE);
        failurePanel.bringToFront();
    }

    private boolean internalNavigation(WebView view, String url) {
        if (url == null) return true;
        Uri uri;
        try { uri = Uri.parse(url); } catch (Exception ex) { return true; }
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        String s = scheme.toLowerCase(Locale.ROOT);
        if (s.equals("https") || s.equals("http") || s.equals("about") || s.equals("data") || s.equals("blob") || s.equals("javascript")) return false;
        if (s.equals("intent")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                String fallback = intent.getStringExtra("browser_fallback_url");
                if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) view.loadUrl(fallback);
                else Toast.makeText(this, "Esse link requer um aplicativo específico.", Toast.LENGTH_LONG).show();
            } catch (Exception ignored) { Toast.makeText(this, "Link não reconhecido.", Toast.LENGTH_SHORT).show(); }
            return true;
        }
        if (s.equals("tel") || s.equals("mailto") || s.equals("sms")) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
            catch (Exception ex) { Toast.makeText(this, "Nenhum aplicativo compatível.", Toast.LENGTH_SHORT).show(); }
            return true;
        }
        // Não abrir navegador externo nem executar esquemas não reconhecidos.
        Toast.makeText(this, "Tipo de link não suportado neste aplicativo.", Toast.LENGTH_SHORT).show();
        return true;
    }

    private final class InternalClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return internalNavigation(view, request.getUrl().toString());
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return internalNavigation(view, url);
        }
        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            if (view == activeWebView()) failurePanel.setVisibility(View.GONE);
            TextView host = popupHosts.get(view);
            if (host != null) {
                String h = Uri.parse(url).getHost();
                host.setText(h == null ? "Nova janela" : h);
            }
        }
        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) showFailure(view, "Não foi possível carregar a página. Verifique a conexão e tente novamente.");
        }
        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() == 404)
                showFailure(view, "Página não encontrada (404). Verifique se o site está publicado no GitHub Pages.");
            else if (request.isForMainFrame() && response.getStatusCode() >= 500)
                showFailure(view, "O servidor está temporariamente indisponível (" + response.getStatusCode() + ").");
        }
    }

    private static final class Popup {
        final WebView web;
        final LinearLayout panel;
        Popup(WebView w, LinearLayout p) { web = w; panel = p; }
    }

    private void closePopup(WebView child) {
        for (int i = popups.size() - 1; i >= 0; i--) {
            Popup popup = popups.get(i);
            if (popup.web == child) {
                popups.remove(i);
                popupHosts.remove(child);
                root.removeView(popup.panel);
                child.stopLoading();
                child.destroy();
                failurePanel.setVisibility(View.GONE);
                break;
            }
        }
    }

    private final class InternalChrome extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int value) {
            if (view != activeWebView()) return;
            progress.setProgress(value);
            progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            progress.bringToFront();
        }
        @Override public boolean onCreateWindow(WebView source, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            if (!isUserGesture || popups.size() >= 8) return false;
            WebView child = createWebView();
            LinearLayout panel = new LinearLayout(MainActivity.this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundColor(PAPER);
            LinearLayout toolbar = new LinearLayout(MainActivity.this);
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            toolbar.setBackgroundColor(PAPER);
            toolbar.setPadding(dp(12), 0, dp(4), 0);
            TextView host = new TextView(MainActivity.this);
            host.setSingleLine(true);
            host.setEllipsize(TextUtils.TruncateAt.END);
            host.setText("Nova janela");
            host.setTextColor(GREEN);
            host.setTextSize(13);
            toolbar.addView(host, new LinearLayout.LayoutParams(0, dp(44), 1));
            host.setGravity(Gravity.CENTER_VERTICAL);
            Button close = new Button(MainActivity.this);
            close.setText("Fechar ×");
            close.setAllCaps(false);
            close.setOnClickListener(v -> closePopup(child));
            toolbar.addView(close, new LinearLayout.LayoutParams(-2, dp(44)));
            panel.addView(toolbar);
            panel.addView(child, new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
            popupHosts.put(child, host);
            popups.add(new Popup(child, panel));
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(child);
            resultMsg.sendToTarget();
            return true;
        }
        @Override public void onCloseWindow(WebView window) { closePopup(window); }
        @Override public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> promptWebMedia(request));
        }
        @Override public void onPermissionRequestCanceled(PermissionRequest request) {
            if (waitingMedia == request) { waitingMedia = null; waitingMediaResources = null; }
        }
        @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            if (origin == null || !origin.startsWith("https://")) { callback.invoke(origin, false, false); return; }
            new AlertDialog.Builder(MainActivity.this)
                .setTitle("Localização")
                .setMessage("Permitir acesso à localização para " + Uri.parse(origin).getHost() + "?")
                .setNegativeButton("Negar", (d, w) -> callback.invoke(origin, false, false))
                .setPositiveButton("Permitir", (d, w) -> {
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        callback.invoke(origin, true, false);
                    } else {
                        waitingGeo = callback;
                        waitingGeoOrigin = origin;
                        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION);
                    }
                }).show();
        }
        @Override public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (picker != null) picker.finish(null);
            picker = new ValueCallbackHolder(callback);
            pendingPickerParams = params;
            boolean cameraPossible = acceptsCamera(params);
            if (cameraPossible && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_FOR_FILES);
            } else openFilePicker();
            return true;
        }
        @Override public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullscreenView != null) { callback.onCustomViewHidden(); return; }
            fullscreenView = view;
            fullscreenCallback = callback;
            root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            view.bringToFront();
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            getWindow().getDecorView().requestApplyInsets();
        }
        @Override public void onHideCustomView() { hideFullscreen(); }
    }

    private void hideFullscreen() {
        if (fullscreenView == null) return;
        root.removeView(fullscreenView);
        fullscreenView = null;
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().getDecorView().requestApplyInsets();
    }

    private void promptWebMedia(PermissionRequest request) {
        Uri origin = request.getOrigin();
        if (origin == null || !"https".equalsIgnoreCase(origin.getScheme())) { request.deny(); return; }
        ArrayList<String> allowed = new ArrayList<>();
        for (String res : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res) || PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) allowed.add(res);
        }
        if (allowed.isEmpty()) { request.deny(); return; }
        if (waitingMedia != null && waitingMedia != request) waitingMedia.deny();
        String host = origin.getHost() == null ? origin.toString() : origin.getHost();
        boolean wantsVideo = allowed.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        boolean wantsAudio = allowed.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE);
        String features = wantsVideo && wantsAudio ? "câmera e microfone" : wantsVideo ? "câmera" : "microfone";
        new AlertDialog.Builder(this).setTitle("Permissão do site")
            .setMessage("Permitir que " + host + " utilize " + features + "?")
            .setNegativeButton("Negar", (d, n) -> request.deny())
            .setOnCancelListener(d -> request.deny())
            .setPositiveButton("Permitir", (d, n) -> {
                ArrayList<String> needed = new ArrayList<>();
                if (wantsVideo && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA);
                if (wantsAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO);
                if (needed.isEmpty()) grantMedia(request, allowed.toArray(new String[0]));
                else {
                    waitingMedia = request;
                    waitingMediaResources = allowed.toArray(new String[0]);
                    requestPermissions(needed.toArray(new String[0]), REQUEST_MEDIA);
                }
            }).show();
    }

    private void grantMedia(PermissionRequest request, String[] resources) {
        ArrayList<String> granted = new ArrayList<>();
        for (String r : resources) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) &&
                    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) granted.add(r);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) &&
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) granted.add(r);
        }
        if (granted.isEmpty()) request.deny();
        else request.grant(granted.toArray(new String[0]));
    }

    private static final class ValueCallbackHolder {
        private final android.webkit.ValueCallback<Uri[]> callback;
        private boolean completed;
        ValueCallbackHolder(android.webkit.ValueCallback<Uri[]> c) { callback = c; }
        void finish(Uri[] uris) {
            if (completed) return;
            completed = true;
            callback.onReceiveValue(uris);
        }
    }

    private boolean acceptsCamera(WebChromeClient.FileChooserParams params) {
        if (params.isCaptureEnabled()) return true;
        for (String t : params.getAcceptTypes()) {
            if (t != null && (t.startsWith("image/") || t.startsWith("video/") || t.equals("*/*"))) return true;
        }
        return params.getAcceptTypes().length == 0;
    }

    private void openFilePicker() {
        if (picker == null || pendingPickerParams == null) return;
        try {
            WebChromeClient.FileChooserParams params = pendingPickerParams;
            if (params.getMode() == WebChromeClient.FileChooserParams.MODE_SAVE) {
                Intent save = params.createIntent();
                startActivityForResult(save, REQUEST_FILES);
                return;
            }
            Intent content = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            content.addCategory(Intent.CATEGORY_OPENABLE);
            String[] accept = params.getAcceptTypes();
            ArrayList<String> types = new ArrayList<>();
            for (String a : accept) if (a != null && !a.trim().isEmpty()) types.add(a.trim());
            content.setType(types.size() == 1 ? types.get(0) : "*/*");
            if (types.size() > 1) content.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
            content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
            ArrayList<Intent> cameraIntents = new ArrayList<>();
            boolean image = types.isEmpty(), video = types.isEmpty();
            for (String type : types) {
                if (type.startsWith("image/") || type.equals("*/*")) image = true;
                if (type.startsWith("video/") || type.equals("*/*")) video = true;
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                if (image) addCapture(cameraIntents, true);
                if (video) addCapture(cameraIntents, false);
            }
            Intent chooser;
            if (params.isCaptureEnabled() && !cameraIntents.isEmpty()) {
                chooser = Intent.createChooser(cameraIntents.remove(0), "Capturar mídia");
            } else chooser = Intent.createChooser(content, "Escolher arquivo");
            if (!cameraIntents.isEmpty()) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, cameraIntents.toArray(new Intent[0]));
            startActivityForResult(chooser, REQUEST_FILES);
        } catch (Exception err) {
            Toast.makeText(this, "Não foi possível abrir os arquivos.", Toast.LENGTH_SHORT).show();
            if (picker != null) picker.finish(null);
            picker = null;
            pendingPickerParams = null;
            clearCaptureFiles(null);
        }
    }

    private void addCapture(ArrayList<Intent> intents, boolean image) {
        try {
            File dir = new File(getCacheDir(), "captures");
            if (!dir.exists() && !dir.mkdirs()) return;
            File file = File.createTempFile(image ? "foto_" : "video_", image ? ".jpg" : ".mp4", dir);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            Intent intent = new Intent(image ? MediaStore.ACTION_IMAGE_CAPTURE : MediaStore.ACTION_VIDEO_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.setClipData(ClipData.newUri(getContentResolver(), "captura", uri));
            if (intent.resolveActivity(getPackageManager()) == null) { file.delete(); return; }
            if (image) { photoUri = uri; photoFile = file; }
            else { videoUri = uri; videoFile = file; }
            intents.add(intent);
        } catch (Exception ignored) { /* câmera opcional; seletor de documentos continua disponível */ }
    }

    private void clearCaptureFiles(Uri used) {
        if (photoFile != null && (photoUri == null || !photoUri.equals(used))) photoFile.delete();
        if (videoFile != null && (videoUri == null || !videoUri.equals(used))) videoFile.delete();
        photoUri = null; videoUri = null; photoFile = null; videoFile = null;
    }

    private Uri[] captureResult() {
        if (photoFile != null && photoFile.length() > 0) return new Uri[]{photoUri};
        if (videoFile != null && videoFile.length() > 0) return new Uri[]{videoUri};
        return null;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILES) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                ClipData clip = data.getClipData();
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) result = new Uri[]{data.getData()};
            else result = captureResult();
        }
        Uri saved = (result != null && result.length == 1) ? result[0] : null;
        clearCaptureFiles(saved);
        if (picker != null) picker.finish(result);
        picker = null;
        pendingPickerParams = null;
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQUEST_MEDIA && waitingMedia != null) {
            PermissionRequest request = waitingMedia;
            String[] resources = waitingMediaResources;
            waitingMedia = null; waitingMediaResources = null;
            grantMedia(request, resources);
        } else if (code == REQUEST_LOCATION && waitingGeo != null) {
            boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            waitingGeo.invoke(waitingGeoOrigin, fine || coarse, false);
            waitingGeo = null; waitingGeoOrigin = null;
        } else if (code == REQUEST_CAMERA_FOR_FILES) {
            openFilePicker(); // mesmo se a câmera for negada, o seletor continua funcional
        } else if (code == REQUEST_STORAGE && waitingDownload != null) {
            DownloadJob job = waitingDownload;
            waitingDownload = null;
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) enqueueDownload(job);
            else Toast.makeText(this, "Permissão de armazenamento negada.", Toast.LENGTH_LONG).show();
        }
    }

    private static final class DownloadJob {
        final String url, agent, disposition, mime;
        DownloadJob(String u, String a, String d, String m) { url=u; agent=a; disposition=d; mime=m; }
    }

    private String fileNameFor(String url, String disposition, String mime) {
        String name = URLUtil.guessFileName(url, disposition, mime);
        if (name == null || name.trim().isEmpty()) name = "arquivo_" + System.currentTimeMillis();
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.length() > 120) name = name.substring(name.length() - 120);
        return name;
    }

    private void onDownload(WebView browser, String url, String agent, String disposition, String mime) {
        if (url == null) return;
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            String name = fileNameFor("arquivo", disposition, mime);
            String js = "(async()=>{try{const r=await fetch(" + JSONObject.quote(url) + ");" +
                "const b=await r.blob();if(b.size>16777216){alert('Arquivo muito grande para download integrado (limite 16 MB).');return;}" +
                "const fr=new FileReader();fr.onload=()=>AndroidBlobBridge.saveBase64(" + JSONObject.quote(name) + ",b.type||" +
                JSONObject.quote(mime == null ? "application/octet-stream" : mime) + ",String(fr.result).split(',')[1]);" +
                "fr.readAsDataURL(b);}catch(e){alert('Não foi possível baixar este arquivo.');}})()";
            browser.evaluateJavascript(js, null);
            return;
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            Toast.makeText(this, "Endereço de download não suportado.", Toast.LENGTH_SHORT).show(); return;
        }
        DownloadJob job = new DownloadJob(url, agent, disposition, mime);
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            waitingDownload = job;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
        } else enqueueDownload(job);
    }

    private void enqueueDownload(DownloadJob job) {
        try {
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(job.url));
            String filename = fileNameFor(job.url, job.disposition, job.mime);
            request.setTitle(filename);
            request.setDescription("Download — IFPI Parnaíba");
            if (job.mime != null && !job.mime.isEmpty()) request.setMimeType(job.mime);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            String cookie = CookieManager.getInstance().getCookie(job.url);
            if (cookie != null) request.addRequestHeader("Cookie", cookie);
            if (job.agent != null && !job.agent.isEmpty()) request.addRequestHeader("User-Agent", job.agent);
            String referer = activeWebView().getUrl();
            if (referer != null && referer.startsWith("http")) request.addRequestHeader("Referer", referer);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            manager.enqueue(request);
            Toast.makeText(this, "Download iniciado: " + filename, Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Toast.makeText(this, "Não foi possível iniciar o download.", Toast.LENGTH_LONG).show();
        }
    }

    private class BlobBridge {
        @JavascriptInterface public void saveBase64(String fileName, String mime, String encoded) {
            if (encoded == null || encoded.length() > MAX_BLOB_BYTES * 4 / 3 + 8) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Arquivo excede 16 MB.", Toast.LENGTH_LONG).show());
                return;
            }
            io.execute(() -> {
                try {
                    byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                    if (bytes.length > MAX_BLOB_BYTES) throw new IllegalArgumentException("Arquivo grande");
                    String safeName = fileNameFor(fileName == null ? "arquivo.bin" : fileName, null, mime);
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
                        values.put(MediaStore.MediaColumns.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (uri == null) throw new IllegalStateException("Falha no armazenamento");
                        try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                            if (stream == null) throw new IllegalStateException("Sem acesso ao arquivo");
                            stream.write(bytes);
                        }
                    } else {
                        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                            throw new SecurityException("Permissão de armazenamento necessária");
                        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Pasta Downloads inacessível");
                        try (OutputStream stream = new FileOutputStream(new File(dir, safeName))) { stream.write(bytes); }
                    }
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Arquivo salvo em Downloads.", Toast.LENGTH_LONG).show());
                } catch (Exception ex) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Não foi possível salvar o arquivo.", Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    @Override public void onBackPressed() {
        if (fullscreenView != null) { hideFullscreen(); return; }
        WebView active = activeWebView();
        if (active.canGoBack()) { active.goBack(); return; }
        if (!popups.isEmpty()) { closePopup(active); return; }
        if (mainView.getUrl() != null && !mainView.getUrl().equals(HOME)) { mainView.loadUrl(HOME); return; }
        super.onBackPressed();
    }

    @Override protected void onPause() {
        if (mainView != null) mainView.onPause();
        for (Popup p : popups) p.web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (mainView != null) mainView.onResume();
        for (Popup p : popups) p.web.onResume();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (mainView != null) mainView.saveState(state);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        if (picker != null) picker.finish(null);
        if (waitingMedia != null) waitingMedia.deny();
        for (int i = popups.size() - 1; i >= 0; i--) closePopup(popups.get(i).web);
        if (mainView != null) { root.removeView(mainView); mainView.destroy(); }
        io.shutdown();
        super.onDestroy();
    }
}
