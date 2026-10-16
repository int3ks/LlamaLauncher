package de.inteks.llamalauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Llama Launcher: startet llama-server in Termux, stellt die Parameter ein (pro Modell gespeichert)
 * und zeigt die Web-UI von llama.cpp in einem eingebauten WebView.
 *
 * Die Oberflaeche wird im Code aufgebaut (keine Layout-Dateien), damit das Projekt ohne
 * Fremdbibliotheken auskommt.
 */
public class MainActivity extends Activity implements TermuxResultReceiver.Listener {

    private enum State { UNKNOWN, STOPPED, STARTING, LOADING, READY, STOPPING }

    private static final int PAGE_CHAT = 0;
    private static final int PAGE_SETTINGS = 1;
    private static final int PAGE_LOG = 2;

    private static final int REQ_PERMISSION = 1;
    private static final int REQ_FILE = 2;

    private static final String[] LANGUAGES = {"", "de", "en"}; // wie R.array.language_labels

    private static final long START_TIMEOUT_MS = 180_000;
    private static final long STOP_TIMEOUT_MS = 15_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // --- Oberflaeche ---
    private TextView statusText;
    private Button btnChat, btnSettings, btnLog, btnStartStop;
    private View chatPage, settingsPage, logPage;
    private WebView web;
    private View chatOverlay;
    private TextView chatOverlayText;
    private TextView logText;
    private ScrollView logScroll;
    private CheckBox cbLogFollow;

    private LinearLayout setupBox;
    private Spinner spModel, spKv, spUb, spThreads, spLoad, spTemplate, spReasoning, spMtp, spLora, spLoraScale, spVerbosity;
    private SeekBar sbCtx;
    private TextView ctxValue;
    private int fieldCount;
    private EditText etCache, etCheckpoints, etDraftN, etExtra;
    private EditText etModelDir, etServer, etLog, etPort;
    private CheckBox cbDraftCpu, cbAutostart;
    private Spinner spLanguage;
    private View mtpOptions, loraOptions;
    private TextView modelInfo, mtpNote, loraNote, reasoningNote, cmdPreview;

    private final List<String> modelItems = new ArrayList<>();
    private final List<String> mtpItems = new ArrayList<>();
    private final List<String> loraItems = new ArrayList<>();
    private ArrayAdapter<String> modelAdapter, mtpAdapter, loraAdapter;

    // --- Zustand ---
    private ModelScanner.Result scan = new ModelScanner.Result();
    private File modelDir;
    private ServerSettings.Features features;
    private String checkMessage = "";
    private boolean suppressEvents;
    private int page = PAGE_SETTINGS;
    private boolean resumed;
    private boolean probing;
    private boolean firstProbeDone;
    private State state = State.UNKNOWN;
    private long startRequestedAt, stopRequestedAt;
    private boolean userStopped;
    private boolean openChatWhenReady;
    private boolean webLoaded;
    private String runningModelName;
    private Runnable pendingStart;
    private String startCmdId;          // Kennung des aktuellen Start-Befehls
    private boolean scanDone;
    private boolean autostartPending;
    private ValueCallback<Uri[]> fileCallback;
    private boolean restored;           // neu aufgebaut (z. B. nach Sprachwechsel), nicht frisch geoeffnet

    // =======================================================================================
    // Lebenszyklus
    // =======================================================================================

    /** Sprache aus den Einstellungen anwenden ("" = Systemsprache). */
    @Override
    protected void attachBaseContext(Context base) {
        String lang = Prefs.language(base);
        if (!lang.isEmpty()) {
            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            cfg.setLocale(Locale.forLanguageTag(lang));
            base = base.createConfigurationContext(cfg);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
        out.putString("startCmdId", startCmdId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE); // Titel steht in der eigenen Kopfzeile
        features = Prefs.features(this);
        View root = buildUi();
        setContentView(root);
        applyEdgeToEdgeInsets(root);
        registerBack();
        TermuxResultReceiver.setListener(this);

        etModelDir.setText(Prefs.modelDir(this));
        etServer.setText(Prefs.serverPath(this));
        etLog.setText(Prefs.logPath(this));
        etPort.setText(String.valueOf(Prefs.port(this)));
        cbAutostart.setChecked(Prefs.autostart(this));

        restored = savedInstanceState != null;
        if (restored) startCmdId = savedInstanceState.getString("startCmdId");
        showPage(restored ? savedInstanceState.getInt("page", PAGE_SETTINGS) : PAGE_SETTINGS);
        rescan();

        Bundle pending = Prefs.takePendingExit(this);
        if (pending != null) ui.post(() -> reportServerExit(pending, true));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        TermuxResultReceiver.setListener(this);
        refreshSetup();
        if (!features.known && TermuxBridge.isTermuxInstalled(this) && TermuxBridge.hasRunCommandPermission(this)
                && checkMessage.isEmpty()) {
            runCheck();
        }
        ui.removeCallbacks(pollTask);
        ui.post(pollTask);
        if (page == PAGE_LOG) startLogRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(pollTask);
        ui.removeCallbacks(logTask);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TermuxResultReceiver.setListener(null);
        ui.removeCallbacksAndMessages(null);
        net.shutdownNow();
        io.shutdownNow();
        if (web != null) web.destroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    private void registerBack() {
        // Ab targetSdk 36 kommt "Zurueck" nur noch ueber diesen Weg (Predictive Back),
        // bei targetSdk 34 bleibt onBackPressed() zustaendig.
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    private void handleBack() {
        if (page == PAGE_CHAT && web.canGoBack()) {
            web.goBack();
        } else if (page != PAGE_CHAT && state == State.READY) {
            showPage(PAGE_CHAT);
        } else {
            moveTaskToBack(true); // App bleibt im Hintergrund, Chat bleibt erhalten
        }
    }

    /** Nur noetig, wenn Android Edge-to-Edge erzwingt (targetSdk >= 35 auf Android 15+). */
    private void applyEdgeToEdgeInsets(View root) {
        if (Build.VERSION.SDK_INT < 35 || getApplicationInfo().targetSdkVersion < 35) return;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    // =======================================================================================
    // Oberflaeche aufbauen
    // =======================================================================================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Kopfzeile: Status + Seiten + Start/Stopp
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(4), 0);
        TextView appTitle = new TextView(this);
        appTitle.setText(getString(R.string.app_name) + "   ");
        appTitle.setTextSize(16);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(appTitle);
        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(statusText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnChat = barButton(getString(R.string.tab_chat), v -> showPage(PAGE_CHAT));
        btnSettings = barButton(getString(R.string.tab_settings), v -> showPage(PAGE_SETTINGS));
        btnLog = barButton(getString(R.string.tab_log), v -> showPage(PAGE_LOG));
        btnStartStop = barButton(getString(R.string.btn_start), v -> onStartStopClicked());
        bar.addView(btnChat);
        bar.addView(btnSettings);
        bar.addView(btnLog);
        bar.addView(btnStartStop);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout pages = new FrameLayout(this);
        chatPage = buildChatPage();
        settingsPage = buildSettingsPage();
        logPage = buildLogPage();
        pages.addView(chatPage, matchParent());
        pages.addView(settingsPage, matchParent());
        pages.addView(logPage, matchParent());
        root.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        updateStatus();
        return root;
    }

    private Button barButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private View buildChatPage() {
        FrameLayout f = new FrameLayout(this);
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);   // die Web-UI speichert die Chats im Browser-Speicher
        ws.setAllowFileAccess(false);
        ws.setSupportZoom(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String host = u.getHost();
                if ("127.0.0.1".equals(host) || "localhost".equals(host)) return false;
                openExternal(u); // fremde Links im Browser oeffnen
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return openFileChooser(callback, params);
            }
        });
        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url.startsWith("http")) openExternal(Uri.parse(url));
            else toast(getString(R.string.toast_no_download));
        });
        f.addView(web, matchParent());

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(dp(24), dp(24), dp(24), dp(24));
        overlay.setClickable(true);
        chatOverlayText = new TextView(this);
        chatOverlayText.setTextSize(17);
        chatOverlayText.setGravity(Gravity.CENTER);
        overlay.addView(chatOverlayText);
        Button go = new Button(this);
        go.setText(R.string.tab_settings);
        go.setAllCaps(false);
        go.setOnClickListener(v -> showPage(PAGE_SETTINGS));
        overlay.addView(go);
        chatOverlay = overlay;
        f.addView(overlay, matchParent());
        return f;
    }

    private View buildLogPage() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(8), 0);
        cbLogFollow = new CheckBox(this);
        cbLogFollow.setText(R.string.log_follow);
        cbLogFollow.setChecked(true);
        row.addView(cbLogFollow);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(smallButton(getString(R.string.btn_copy), v -> {
            copyToClipboard("llama-server.log", logText.getText().toString());
            toast(getString(R.string.toast_log_copied));
        }));
        row.addView(smallButton(getString(R.string.btn_refresh), v -> refreshLog()));
        l.addView(row);

        logScroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextSize(11);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(8), dp(4), dp(8), dp(8));
        logScroll.addView(logText);
        l.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return l;
    }

    private View buildSettingsPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(8), dp(16), dp(32));
        sv.addView(c);

        setupBox = new LinearLayout(this);
        setupBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(setupBox);

        // --- Modell ---
        c.addView(title(getString(R.string.title_model)));
        modelAdapter = adapter(modelItems);
        spModel = new Spinner(this);
        spModel.setAdapter(modelAdapter);
        spModel.setOnItemSelectedListener(new SimpleSelect(() -> onModelSelected()));
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        modelRow.addView(spModel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modelRow.addView(smallButton(getString(R.string.btn_rescan), v -> rescan()));
        c.addView(modelRow);
        modelInfo = hint("");
        c.addView(modelInfo);

        // --- MTP ---
        c.addView(title(getString(R.string.title_mtp)));
        c.addView(hint(getString(R.string.hint_mtp)));
        mtpAdapter = adapter(mtpItems);
        spMtp = new Spinner(this);
        spMtp.setAdapter(mtpAdapter);
        spMtp.setOnItemSelectedListener(new SimpleSelect(this::onFormChanged));
        c.addView(spMtp);
        mtpNote = hint("");
        c.addView(mtpNote);
        LinearLayout mtpBox = new LinearLayout(this);
        mtpBox.setOrientation(LinearLayout.VERTICAL);
        etDraftN = edit(InputType.TYPE_CLASS_NUMBER);
        field(mtpBox, R.string.label_draft_n, etDraftN, R.string.hint_draft_n);
        cbDraftCpu = new CheckBox(this);
        cbDraftCpu.setText(R.string.cb_draft_cpu);
        cbDraftCpu.setOnCheckedChangeListener((b, x) -> onFormChanged());
        mtpBox.addView(cbDraftCpu);
        mtpOptions = mtpBox;
        c.addView(mtpBox);

        // --- LoRA ---
        c.addView(title(getString(R.string.title_lora)));
        c.addView(hint(getString(R.string.hint_lora)));
        loraAdapter = adapter(loraItems);
        spLora = new Spinner(this);
        spLora.setAdapter(loraAdapter);
        spLora.setOnItemSelectedListener(new SimpleSelect(this::onFormChanged));
        c.addView(spLora);
        loraNote = hint("");
        c.addView(loraNote);
        LinearLayout loraBox = new LinearLayout(this);
        loraBox.setOrientation(LinearLayout.VERTICAL);
        spLoraScale = spinner(ServerSettings.LORA_SCALES);
        field(loraBox, R.string.label_lora_scale, spLoraScale, R.string.hint_lora_scale);
        loraOptions = loraBox;
        c.addView(loraBox);

        // --- Grundeinstellungen ---
        c.addView(title(getString(R.string.title_context)));
        LinearLayout ctxBox = new LinearLayout(this);
        ctxBox.setOrientation(LinearLayout.HORIZONTAL);
        ctxBox.setGravity(Gravity.CENTER_VERTICAL);
        sbCtx = new SeekBar(this);
        sbCtx.setMax(CTX_MAX_K - 1); // Schritte 1K .. 64K
        ctxValue = new TextView(this);
        ctxValue.setTextSize(15);
        ctxValue.setTypeface(Typeface.DEFAULT_BOLD);
        ctxValue.setMinWidth(dp(110));
        ctxBox.addView(sbCtx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ctxBox.addView(ctxValue);
        sbCtx.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                updateCtxLabel();
                onFormChanged();
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        });
        field(c, R.string.label_ctx, ctxBox, R.string.hint_ctx);
        spKv = spinner(getResources().getStringArray(R.array.kv_labels));
        field(c, R.string.label_kv, spKv, R.string.hint_kv);
        etCache = edit(InputType.TYPE_CLASS_NUMBER);
        field(c, R.string.label_cache, etCache, R.string.hint_cache);
        etCheckpoints = edit(InputType.TYPE_CLASS_NUMBER);
        field(c, R.string.label_ckpt, etCheckpoints, R.string.hint_ckpt);

        c.addView(title(getString(R.string.title_gpu)));
        spUb = spinner(getResources().getStringArray(R.array.ub_labels));
        field(c, R.string.label_ub, spUb, R.string.hint_ub);
        String[] threadLabels = new String[8];
        for (int i = 0; i < 8; i++) threadLabels[i] = String.valueOf(i + 1);
        spThreads = spinner(threadLabels);
        field(c, R.string.label_threads, spThreads, R.string.hint_threads);
        spLoad = spinner(getResources().getStringArray(R.array.load_labels));
        field(c, R.string.label_load, spLoad, R.string.hint_load);

        c.addView(title(getString(R.string.title_chat)));
        spTemplate = spinner(getResources().getStringArray(R.array.template_labels));
        field(c, R.string.label_template, spTemplate, 0);
        spReasoning = spinner(getResources().getStringArray(R.array.reasoning_labels));
        field(c, R.string.label_reasoning, spReasoning, R.string.hint_reasoning);
        reasoningNote = hint("");
        c.addView(reasoningNote);

        c.addView(title(getString(R.string.title_more)));
        spVerbosity = spinner(getResources().getStringArray(R.array.verbosity_labels));
        field(c, R.string.label_verbosity, spVerbosity, 0);
        etExtra = edit(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field(c, R.string.label_extra, etExtra, R.string.hint_extra);

        c.addView(title(getString(R.string.title_cmdline)));
        cmdPreview = new TextView(this);
        cmdPreview.setTypeface(Typeface.MONOSPACE);
        cmdPreview.setTextSize(12);
        cmdPreview.setTextIsSelectable(true);
        c.addView(cmdPreview);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);
        Button start = new Button(this);
        start.setText(R.string.btn_start_server);
        start.setAllCaps(false);
        start.setOnClickListener(v -> startServer());
        actions.addView(start, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(smallButton(getString(R.string.btn_load_defaults), v -> {
            applyToForm(new ServerSettings());
            toast(getString(R.string.toast_defaults_loaded));
        }));
        c.addView(actions);
        cbAutostart = new CheckBox(this);
        cbAutostart.setText(R.string.cb_autostart);
        cbAutostart.setOnCheckedChangeListener((b, x) -> Prefs.setAutostart(this, x));
        c.addView(cbAutostart);

        // --- App ---
        c.addView(title(getString(R.string.title_app)));
        spLanguage = new Spinner(this);
        spLanguage.setAdapter(adapter(new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.language_labels)))));
        spLanguage.setSelection(Math.max(0, indexOf(LANGUAGES, Prefs.language(this))), false);
        spLanguage.setOnItemSelectedListener(new SimpleSelect(() -> {
            String lang = LANGUAGES[Math.max(0, spLanguage.getSelectedItemPosition())];
            if (!lang.equals(Prefs.language(this))) {
                Prefs.setLanguage(this, lang);
                recreate();
            }
        }));
        field(c, R.string.label_language, spLanguage, R.string.hint_language);

        // --- Pfade ---
        c.addView(title(getString(R.string.title_paths)));
        etModelDir = edit(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field(c, R.string.label_model_dir, etModelDir, 0);
        etServer = edit(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field(c, R.string.label_server, etServer, 0);
        etLog = edit(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field(c, R.string.label_log, etLog, R.string.hint_log);
        etPort = edit(InputType.TYPE_CLASS_NUMBER);
        field(c, R.string.label_port, etPort, 0);
        LinearLayout pathRow = new LinearLayout(this);
        pathRow.setOrientation(LinearLayout.HORIZONTAL);
        pathRow.addView(smallButton(getString(R.string.btn_save_paths), v -> savePaths()));
        pathRow.addView(smallButton(getString(R.string.btn_webui_browser), v -> openExternal(Uri.parse(serverUrl()))));
        pathRow.addView(smallButton(getString(R.string.btn_open_termux), v -> openTermux()));
        c.addView(pathRow);

        // Aenderungen an Textfeldern aktualisieren die Kommandozeile
        TextWatcher tw = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            public void onTextChanged(CharSequence s, int a, int b, int d) { }
            public void afterTextChanged(Editable e) { onFormChanged(); }
        };
        for (EditText e : new EditText[]{etCache, etCheckpoints, etDraftN, etExtra}) e.addTextChangedListener(tw);
        return sv;
    }

    // --- kleine Helfer fuer die Oberflaeche ---

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private TextView title(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(17);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(18), 0, dp(2));
        return v;
    }

    private TextView hint(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13);
        v.setTextColor(hintColor());
        return v;
    }

    private Button smallButton(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    private EditText edit(int inputType) {
        EditText e = new EditText(this);
        e.setInputType(inputType);
        e.setSingleLine(true);
        return e;
    }

    private ArrayAdapter<String> adapter(List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        s.setAdapter(adapter(new ArrayList<>(Arrays.asList(items))));
        s.setOnItemSelectedListener(new SimpleSelect(this::onFormChanged));
        return s;
    }

    /** Eine Einstellungszeile: links Name und Erklaerung, rechts das Eingabefeld; Zeilen abwechselnd hinterlegt. */
    private void field(LinearLayout parent, int labelRes, View input, int hintRes) {
        field(parent, getString(labelRes), input, hintRes != 0 ? getString(hintRes) : null);
    }

    private void field(LinearLayout parent, String label, View input, String hintText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        if (fieldCount++ % 2 == 0) row.setBackgroundColor(0x1A808080); // halbtransparentes Grau: passt zu hell und dunkel
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(0, 0, dp(12), 0);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(15);
        l.setTextColor(labelColor());
        left.addView(l);
        if (hintText != null) left.addView(hint(hintText));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private boolean isDark() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Beschriftung: dunkel = helles Pastellgelb, hell = dunkles Braun. */
    private int labelColor() {
        return isDark() ? 0xFFFFF6E5 : 0xFF3A2A00;
    }

    private int hintColor() {
        return isDark() ? 0xCCFFF6E5 : 0xB33A2A00;
    }

    private static final int CTX_MAX_K = 64;

    private void updateCtxLabel() {
        int k = sbCtx.getProgress() + 1;
        ctxValue.setText("  " + k + "K (" + (k * 1024) + ")");
    }

    private final class SimpleSelect implements AdapterView.OnItemSelectedListener {
        private final Runnable r;

        SimpleSelect(Runnable r) {
            this.r = r;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (!suppressEvents) r.run();
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    private void toast(String t) {
        Toast.makeText(this, t, Toast.LENGTH_LONG).show();
    }

    private void alert(String title, String msg) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton(android.R.string.ok, null).show();
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.toast_no_app));
        }
    }

    private void openTermux() {
        Intent i = getPackageManager().getLaunchIntentForPackage(TermuxBridge.TERMUX_PACKAGE);
        if (i != null) startActivity(i);
        else toast(getString(R.string.toast_no_termux));
    }

    // =======================================================================================
    // Seiten
    // =======================================================================================

    private void showPage(int p) {
        page = p;
        chatPage.setVisibility(p == PAGE_CHAT ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(p == PAGE_SETTINGS ? View.VISIBLE : View.GONE);
        logPage.setVisibility(p == PAGE_LOG ? View.VISIBLE : View.GONE);
        btnChat.setTypeface(null, p == PAGE_CHAT ? Typeface.BOLD : Typeface.NORMAL);
        btnSettings.setTypeface(null, p == PAGE_SETTINGS ? Typeface.BOLD : Typeface.NORMAL);
        btnLog.setTypeface(null, p == PAGE_LOG ? Typeface.BOLD : Typeface.NORMAL);
        ui.removeCallbacks(logTask);
        if (p == PAGE_LOG && resumed) startLogRefresh();
        if (p == PAGE_CHAT && state == State.READY && !webLoaded) loadWebUi();
        updateStatus();
    }

    // =======================================================================================
    // Einrichtung (Termux, Berechtigungen)
    // =======================================================================================

    private void refreshSetup() {
        setupBox.removeAllViews();
        boolean termux = TermuxBridge.isTermuxInstalled(this);
        boolean perm = termux && TermuxBridge.hasRunCommandPermission(this);
        boolean files = Environment.isExternalStorageManager();
        boolean allOk = termux && perm && files && features.known;

        setupBox.addView(title(getString(allOk ? R.string.setup_title_ok : R.string.setup_title)));
        if (!allOk) {
            setupBox.addView(check(termux, getString(R.string.setup_termux), termux ? null : getString(R.string.setup_termux_hint)));
            LinearLayout r1 = check(perm, getString(R.string.setup_perm), null);
            if (termux && !perm) r1.addView(smallButton(getString(R.string.btn_grant), v -> requestRunCommand()));
            setupBox.addView(r1);
            LinearLayout r2 = check(files, getString(R.string.setup_files), null);
            if (!files) r2.addView(smallButton(getString(R.string.btn_allow), v -> requestAllFiles()));
            setupBox.addView(r2);
        }
        LinearLayout r3 = check(features.known, getString(R.string.setup_test), checkMessage.isEmpty() ? null : checkMessage);
        if (perm) r3.addView(smallButton(getString(features.known ? R.string.btn_retest : R.string.btn_test), v -> runCheck()));
        setupBox.addView(r3);
        if (!allOk) {
            setupBox.addView(hint(getString(R.string.setup_props_hint)));
            String cmd = "mkdir -p ~/.termux && echo \"allow-external-apps = true\" >> ~/.termux/termux.properties && termux-reload-settings";
            TextView tv = new TextView(this);
            tv.setText(cmd);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(12);
            tv.setTextIsSelectable(true);
            setupBox.addView(tv);
            LinearLayout r4 = new LinearLayout(this);
            r4.addView(smallButton(getString(R.string.btn_copy_cmd), v -> {
                copyToClipboard("termux", cmd);
                toast(getString(R.string.toast_copied_termux));
            }));
            r4.addView(smallButton(getString(R.string.btn_open_termux), v -> openTermux()));
            setupBox.addView(r4);
        }
    }

    private LinearLayout check(boolean ok, String text, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText((ok ? "✓  " : "✗  ") + text + (detail == null ? "" : "\n     " + detail));
        t.setTextSize(14);
        t.setTextColor(ok ? Color.rgb(0x2E, 0x9E, 0x4F) : Color.rgb(0xD3, 0x2F, 0x2F));
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void requestRunCommand() {
        // Termux definiert die Berechtigung als "dangerous" -> normaler Berechtigungsdialog
        requestPermissions(new String[]{TermuxBridge.PERMISSION_RUN_COMMAND}, REQ_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSION) return;
        if (TermuxBridge.hasRunCommandPermission(this)) {
            refreshSetup();
            runCheck();
        } else {
            // Dialog abgelehnt oder nicht angezeigt -> App-Einstellungen oeffnen
            toast(getString(R.string.toast_perm_settings));
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    private void requestAllFiles() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void runCheck() {
        String err = TermuxBridge.runScript(this, TermuxBridge.CMD_CHECK, "Llama Launcher: Test",
                TermuxBridge.SCRIPT_CHECK, List.of(etServer.getText().toString().trim()));
        checkMessage = err != null ? err : getString(R.string.check_running);
        refreshSetup();
    }

    // =======================================================================================
    // Modelle und Formular
    // =======================================================================================

    private void rescan() {
        modelDir = new File(etModelDir.getText().toString().trim());
        final File dir = modelDir;
        modelInfo.setText(R.string.scan_reading);
        io.execute(() -> {
            ModelScanner.Result r = ModelScanner.scan(dir);
            ui.post(() -> onScanDone(r));
        });
    }

    private void onScanDone(ModelScanner.Result r) {
        scan = r;
        suppressEvents = true;
        modelItems.clear();
        for (File f : r.models) {
            modelItems.add(ModelScanner.displayName(f, modelDir) + "   (" + ModelScanner.humanSize(f.length()) + ")");
        }
        if (modelItems.isEmpty()) modelItems.add(getString(R.string.no_models));
        modelAdapter.notifyDataSetChanged();
        int sel = 0;
        String last = Prefs.lastModel(this);
        for (int i = 0; i < r.models.size(); i++) if (r.models.get(i).getPath().equals(last)) sel = i;
        spModel.setSelection(sel, false);
        suppressEvents = false;
        onModelSelected();
        scanDone = true;
        tryAutostart();
        if (r.error != 0) modelInfo.setText(getString(r.error, r.errorPath));
        else if (!Environment.isExternalStorageManager()) modelInfo.setText(R.string.scan_no_files_perm);
    }

    private File selectedModel() {
        int i = spModel.getSelectedItemPosition();
        return (i >= 0 && i < scan.models.size()) ? scan.models.get(i) : null;
    }

    private void onModelSelected() {
        File m = selectedModel();
        ServerSettings s = null;
        StringBuilder info = new StringBuilder();
        if (m != null) {
            s = Prefs.preset(this, m.getName());
            File mm = ModelScanner.findMmproj(m);
            info.append(getString(s != null ? R.string.preset_loaded : R.string.preset_none));
            if (mm != null) info.append('\n').append(getString(R.string.mmproj_loaded, mm.getName()));
        }
        if (s == null) s = new ServerSettings();
        rebuildMtpLoraLists(m);
        applyToForm(s);
        modelInfo.setText(info.toString());
    }

    /** MTP- und LoRA-Liste mit Markierung "passt zum Modell" neu aufbauen. */
    private void rebuildMtpLoraLists(File model) {
        String mName = model != null ? model.getName() : "";
        String tag = ModelScanner.sizeTag(mName);
        mtpItems.clear();
        mtpItems.add(getString(R.string.mtp_off));
        for (File f : scan.mtps) {
            mtpItems.add(ModelScanner.displayName(f, modelDir) + "   (" + ModelScanner.humanSize(f.length()) + ")"
                    + (ModelScanner.mtpMatches(f.getName(), tag) ? getString(R.string.mark_mtp_match) : ""));
        }
        loraItems.clear();
        loraItems.add(getString(R.string.lora_none));
        for (File f : scan.loras) {
            loraItems.add(ModelScanner.displayName(f, modelDir) + "   (" + ModelScanner.humanSize(f.length()) + ")"
                    + (ModelScanner.loraMatches(f.getName(), mName) ? getString(R.string.mark_lora_match) : ""));
        }
        mtpAdapter.notifyDataSetChanged();
        loraAdapter.notifyDataSetChanged();
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return -1;
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    private static int indexOfPath(List<File> files, String path) {
        for (int i = 0; i < files.size(); i++) if (files.get(i).getPath().equals(path)) return i;
        return -1;
    }

    private void applyToForm(ServerSettings s) {
        suppressEvents = true;
        sbCtx.setProgress(Math.max(1, Math.min(CTX_MAX_K, Math.round(s.ctx / 1024f))) - 1);
        updateCtxLabel();
        spKv.setSelection(Math.max(0, indexOf(ServerSettings.KV_TYPES, s.kv)), false);
        int ub = indexOf(ServerSettings.UB_VALUES, s.ub);
        spUb.setSelection(ub >= 0 ? ub : 0, false);
        etCache.setText(String.valueOf(s.cacheRam));
        etCheckpoints.setText(String.valueOf(s.checkpoints));
        spThreads.setSelection(Math.min(7, Math.max(0, s.threads - 1)), false);
        int lm = indexOf(ServerSettings.LOAD_MODES, s.loadMode);
        spLoad.setSelection(lm >= 0 ? lm : 5, false);
        spTemplate.setSelection(s.noJinja ? 1 : 0, false);
        spReasoning.setSelection(Math.max(0, indexOf(ServerSettings.REASONING_VALUES, s.reasoning)), false);
        spVerbosity.setSelection(Math.min(3, Math.max(0, s.verbosity - 1)), false);
        int mtp = s.mtpPath.isEmpty() ? -1 : indexOfPath(scan.mtps, s.mtpPath);
        spMtp.setSelection(mtp + 1, false);
        mtpNote.setText(!s.mtpPath.isEmpty() && mtp < 0 ? getString(R.string.note_saved_mtp_missing) : "");
        etDraftN.setText(String.valueOf(s.draftN));
        cbDraftCpu.setChecked(s.draftCpu);
        int lora = s.loraPath.isEmpty() ? -1 : indexOfPath(scan.loras, s.loraPath);
        spLora.setSelection(lora + 1, false);
        loraNote.setText(!s.loraPath.isEmpty() && lora < 0 ? getString(R.string.note_saved_lora_missing) : "");
        int sc = indexOf(ServerSettings.LORA_SCALES, s.loraScale);
        spLoraScale.setSelection(sc >= 0 ? sc : 3, false);
        etExtra.setText(s.extraArgs);
        suppressEvents = false;
        onFormChanged();
    }

    /** Formular auslesen. strict = Fehler melden und null liefern; sonst Vorgaben einsetzen. */
    private ServerSettings readForm(boolean strict) {
        ServerSettings s = new ServerSettings();
        s.ctx = (sbCtx.getProgress() + 1) * 1024;
        s.kv = ServerSettings.KV_TYPES[Math.max(0, spKv.getSelectedItemPosition())];
        s.ub = ServerSettings.UB_VALUES[Math.max(0, spUb.getSelectedItemPosition())];
        s.cacheRam = parseInt(etCache, s.cacheRam, 0);
        s.checkpoints = parseInt(etCheckpoints, s.checkpoints, 0);
        s.threads = spThreads.getSelectedItemPosition() + 1;
        s.loadMode = ServerSettings.LOAD_MODES[Math.max(0, spLoad.getSelectedItemPosition())];
        s.noJinja = spTemplate.getSelectedItemPosition() == 1;
        s.reasoning = ServerSettings.REASONING_VALUES[Math.max(0, spReasoning.getSelectedItemPosition())];
        s.verbosity = spVerbosity.getSelectedItemPosition() + 1;
        int m = spMtp.getSelectedItemPosition() - 1;
        s.mtpPath = (m >= 0 && m < scan.mtps.size()) ? scan.mtps.get(m).getPath() : "";
        s.draftN = parseInt(etDraftN, ServerSettings.LLAMA_DEF_DRAFT_N, 1);
        s.draftCpu = cbDraftCpu.isChecked();
        int l = spLora.getSelectedItemPosition() - 1;
        s.loraPath = (l >= 0 && l < scan.loras.size()) ? scan.loras.get(l).getPath() : "";
        s.loraScale = ServerSettings.LORA_SCALES[Math.max(0, spLoraScale.getSelectedItemPosition())];
        s.extraArgs = etExtra.getText().toString().trim();
        return s;
    }

    private static int parseInt(EditText e, int def, int min) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return v >= min ? v : def;
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private int currentPort() {
        try {
            return Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            return Prefs.port(this);
        }
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + currentPort();
    }

    private void onFormChanged() {
        if (suppressEvents || cmdPreview == null) return;
        mtpOptions.setVisibility(spMtp.getSelectedItemPosition() > 0 ? View.VISIBLE : View.GONE);
        loraOptions.setVisibility(spLora.getSelectedItemPosition() > 0 ? View.VISIBLE : View.GONE);
        boolean jinja = spTemplate.getSelectedItemPosition() == 0;
        spReasoning.setEnabled(jinja && features.reasoning);
        reasoningNote.setText(!jinja ? getString(R.string.note_nojinja_reasoning)
                : !features.reasoning ? getString(R.string.note_no_reasoning) : "");
        spMtp.setEnabled(features.specType);
        if (!features.specType) mtpNote.setText(R.string.note_no_spec);
        else if (spMtp.getSelectedItemPosition() > 0) mtpNote.setText("");
        spLora.setEnabled(features.loraScaled);
        if (!features.loraScaled) loraNote.setText(R.string.note_no_lora);
        else if (spLora.getSelectedItemPosition() > 0) loraNote.setText("");

        File m = selectedModel();
        ServerSettings s = readForm(false);
        if (m == null) {
            cmdPreview.setText(R.string.cmd_no_model);
            return;
        }
        File mm = ModelScanner.findMmproj(m);
        ServerSettings.Built b = s.buildArgs(etServer.getText().toString().trim(), m.getPath(),
                mm != null ? mm.getPath() : null, currentPort(), features);
        StringBuilder t = new StringBuilder(ServerSettings.toShellLine(b.args));
        if (b.error != 0) t.append("\n\n").append(getString(R.string.cmd_error, getString(b.error)));
        for (int n : b.notes) t.append('\n').append(getString(R.string.cmd_note, getString(n)));
        if (!features.known) t.append("\n\n").append(getString(R.string.cmd_untested));
        cmdPreview.setText(t.toString());
    }

    private void savePaths() {
        int port = currentPort();
        Prefs.savePaths(this, etModelDir.getText().toString().trim(), etServer.getText().toString().trim(),
                etLog.getText().toString().trim(), port);
        toast(getString(R.string.toast_paths_saved));
        rescan();
        if (TermuxBridge.hasRunCommandPermission(this)) runCheck();
    }

    // =======================================================================================
    // Server starten / stoppen
    // =======================================================================================

    private void onStartStopClicked() {
        if (state == State.READY || state == State.LOADING || state == State.STARTING) stopServer(false);
        else startServer();
    }

    private void startServer() {
        if (!TermuxBridge.isTermuxInstalled(this) || !TermuxBridge.hasRunCommandPermission(this)) {
            showPage(PAGE_SETTINGS);
            alert(getString(R.string.dlg_setup_missing), getString(R.string.msg_setup_missing_start));
            return;
        }
        File model = selectedModel();
        if (model == null) {
            showPage(PAGE_SETTINGS);
            alert(getString(R.string.dlg_no_model), getString(R.string.msg_no_model));
            return;
        }
        ServerSettings s = readForm(true);
        if (s == null) return;
        File mm = ModelScanner.findMmproj(model);
        String serverPath = etServer.getText().toString().trim();
        ServerSettings.Built b = s.buildArgs(serverPath, model.getPath(), mm != null ? mm.getPath() : null, currentPort(), features);
        if (b.error != 0) {
            alert(getString(R.string.dlg_cannot_start), getString(b.error));
            return;
        }
        Runnable doStart = () -> launch(model, s, b);
        if (state == State.READY || state == State.LOADING || state == State.STARTING) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dlg_running)
                    .setMessage(R.string.msg_restart)
                    .setPositiveButton(R.string.btn_restart, (d, w) -> {
                        pendingStart = doStart;
                        stopServer(true);
                    })
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show();
        } else {
            doStart.run();
        }
    }

    private void launch(File model, ServerSettings s, ServerSettings.Built b) {
        Prefs.savePreset(this, model.getName(), s);
        Prefs.setLastModel(this, model.getPath());
        List<String> args = new ArrayList<>();
        args.add(etLog.getText().toString().trim());
        args.addAll(b.args);
        String cmdId = TermuxBridge.CMD_START + ":" + System.currentTimeMillis();
        String err = TermuxBridge.runScript(this, cmdId, "llama-server (Llama Launcher)",
                TermuxBridge.SCRIPT_START, args);
        if (err != null) {
            alert(getString(R.string.dlg_start_failed), err);
            return;
        }
        for (int n : b.notes) toast(getString(n));
        startCmdId = cmdId;
        state = State.STARTING;
        startRequestedAt = System.currentTimeMillis();
        userStopped = false;
        runningModelName = model.getName();
        openChatWhenReady = true;
        webLoaded = false;
        showPage(PAGE_LOG);
        updateStatus();
        ui.removeCallbacks(pollTask);
        ui.postDelayed(pollTask, 800);
    }

    private void stopServer(boolean forRestart) {
        if (!TermuxBridge.hasRunCommandPermission(this)) {
            alert(getString(R.string.dlg_setup_missing), getString(R.string.msg_setup_missing_stop));
            return;
        }
        String err = TermuxBridge.runScript(this, TermuxBridge.CMD_STOP, "Stop llama-server (Llama Launcher)",
                TermuxBridge.SCRIPT_STOP, List.of(String.valueOf(currentPort())));
        if (err != null) {
            pendingStart = null;
            alert(getString(R.string.dlg_stop_failed), err);
            return;
        }
        if (!forRestart) pendingStart = null;
        state = State.STOPPING;
        stopRequestedAt = System.currentTimeMillis();
        userStopped = true;
        updateStatus();
        ui.removeCallbacks(pollTask);
        ui.postDelayed(pollTask, 500);
    }

    // =======================================================================================
    // Ergebnisse aus Termux
    // =======================================================================================

    @Override
    public void onTermuxResult(String cmd, Bundle result) {
        String errmsg = result.getString(TermuxBridge.RESULT_ERRMSG);
        boolean failed = errmsg != null && !errmsg.trim().isEmpty();
        if (TermuxBridge.CMD_CHECK.equals(cmd)) {
            String out = result.getString(TermuxBridge.RESULT_STDOUT, "");
            if (failed) {
                checkMessage = getString(R.string.check_rejected, shorten(errmsg));
            } else if (out.contains("NO_SERVER")) {
                checkMessage = getString(R.string.check_no_server, etServer.getText().toString().trim());
            } else if (out.contains("SERVER_OK")) {
                features = ServerSettings.Features.fromCheckOutput(out);
                Prefs.saveFeatures(this, features);
                checkMessage = getString(R.string.check_ok, featureSummary());
            } else {
                checkMessage = getString(R.string.check_unexpected, shorten(out + " " + result.getString(TermuxBridge.RESULT_STDERR, "")));
            }
            refreshSetup();
            onFormChanged();
        } else if (TermuxBridge.CMD_STOP.equals(cmd)) {
            if (failed) alert(getString(R.string.dlg_stop_failed), shorten(errmsg));
            else if (result.getInt(TermuxBridge.RESULT_EXIT_CODE, 0) != 0) toast(getString(R.string.toast_stop_failed));
        } else if (cmd != null && cmd.startsWith(TermuxBridge.CMD_START)) {
            if (startCmdId == null) {
                // Start stammt aus einer frueheren Sitzung der App: nur melden, wenn der Server wirklich weg ist
                if (state == State.STOPPED || state == State.UNKNOWN) reportServerExit(result, true);
            } else if (cmd.equals(startCmdId)) {
                startCmdId = null;
                reportServerExit(result, false);
            }
            // sonst: Ende eines aelteren Starts (z. B. beim Neustart) -> ignorieren
        }
    }

    private String featureSummary() {
        List<String> missing = new ArrayList<>();
        if (!features.loadMode) missing.add("--load-mode");
        if (!features.noJinja) missing.add("--no-jinja");
        if (!features.specType) missing.add("--spec-type");
        if (!features.loraScaled) missing.add("--lora-scaled");
        if (!features.reasoning) missing.add("--reasoning");
        return missing.isEmpty() ? getString(R.string.features_all)
                : getString(R.string.features_missing, String.join(", ", missing));
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 600 ? s.substring(0, 600) + " ..." : s;
    }

    /** Der Server-Prozess (Start-Befehl) ist beendet. */
    private void reportServerExit(Bundle result, boolean whileClosed) {
        String errmsg = result.getString(TermuxBridge.RESULT_ERRMSG);
        if (errmsg != null && !errmsg.trim().isEmpty()) {
            if (state == State.STARTING) state = State.STOPPED;
            updateStatus();
            alert(getString(R.string.dlg_start_rejected), getString(R.string.msg_start_rejected, shorten(errmsg)));
            return;
        }
        boolean expected = !whileClosed && userStopped && System.currentTimeMillis() - stopRequestedAt < 60_000;
        if (state == State.STARTING || state == State.LOADING || state == State.READY) state = State.STOPPED;
        webLoaded = false;
        updateStatus();
        if (expected) return;
        int code = result.containsKey(TermuxBridge.RESULT_EXIT_CODE)
                ? result.getInt(TermuxBridge.RESULT_EXIT_CODE) : Integer.MIN_VALUE;
        String reason;
        switch (code) {
            case 0: reason = getString(R.string.exit_normal); break;
            case 90: reason = getString(R.string.exit_90); break;
            case 91: reason = getString(R.string.exit_91); break;
            case 134: reason = getString(R.string.exit_134); break;
            case 137: reason = getString(R.string.exit_137); break;
            case 139: reason = getString(R.string.exit_139); break;
            case Integer.MIN_VALUE: reason = getString(R.string.exit_none); break;
            default: reason = getString(R.string.exit_code, code); break;
        }
        String tail = lastLines(readLogTail(etLog.getText().toString().trim(), 8192), 15);
        alert(getString(whileClosed ? R.string.dlg_exited_closed : R.string.dlg_exited),
                reason + (tail.isEmpty() ? "" : "\n\n" + getString(R.string.last_log_lines) + "\n" + tail));
    }

    // =======================================================================================
    // Status (Abfrage von /health)
    // =======================================================================================

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            if (!probing) {
                probing = true;
                final int port = currentPort();
                net.execute(() -> {
                    int code = probe(port, "/health");
                    ui.post(() -> {
                        probing = false;
                        onHealth(code);
                    });
                });
            }
            boolean fast = state == State.STARTING || state == State.LOADING || state == State.STOPPING;
            ui.postDelayed(this, fast ? 1000 : 3000);
        }
    };

    /** HTTP-Statuscode oder -1, wenn der Server nicht erreichbar ist. */
    private static int probe(int port, String path) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
            c.setConnectTimeout(700);
            c.setReadTimeout(2000);
            c.setUseCaches(false);
            return c.getResponseCode();
        } catch (IOException e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void onHealth(int code) {
        State old = state;
        long now = System.currentTimeMillis();
        if (state == State.STOPPING && code > 0 && now - stopRequestedAt < STOP_TIMEOUT_MS) {
            // laeuft noch, Stoppen ist unterwegs
        } else if (code == 503) {
            state = State.LOADING;
        } else if (code > 0) {
            state = State.READY;
        } else if (state == State.STARTING && now - startRequestedAt < START_TIMEOUT_MS) {
            // Server lauscht noch nicht
        } else {
            state = State.STOPPED;
        }

        if (state == State.READY && old != State.READY) onBecameReady();
        if (state != State.READY && old == State.READY) webLoaded = false;
        if (state == State.STOPPED && pendingStart != null) {
            Runnable r = pendingStart;
            pendingStart = null;
            r.run();
        }
        if (!firstProbeDone) {
            firstProbeDone = true;
            onFirstProbe();
        }
        updateStatus();
    }

    private void onFirstProbe() {
        if (restored) return; // nach dem Neuaufbau: Seite behalten, kein Autostart
        if (state == State.READY) {
            showPage(PAGE_CHAT);
        } else if (state == State.STOPPED && cbAutostart.isChecked()) {
            autostartPending = true;
            tryAutostart();
        }
    }

    /** Autostart erst, wenn Modellliste und erster Status da sind. */
    private void tryAutostart() {
        if (!autostartPending || !scanDone || !firstProbeDone) return;
        autostartPending = false;
        if (state == State.STOPPED && selectedModel() != null) startServer();
    }

    private void onBecameReady() {
        if (runningModelName == null) fetchModelName();
        if (openChatWhenReady || page == PAGE_CHAT) {
            openChatWhenReady = false;
            showPage(PAGE_CHAT);
        }
        if (page == PAGE_CHAT) loadWebUi();
    }

    private void loadWebUi() {
        webLoaded = true;
        web.loadUrl(serverUrl());
    }

    /** Wurde der Server woanders gestartet (z. B. llama-start.sh), den Modellnamen aus /props holen. */
    private void fetchModelName() {
        final int port = currentPort();
        net.execute(() -> {
            String name = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/props").openConnection();
                c.setConnectTimeout(700);
                c.setReadTimeout(2000);
                if (c.getResponseCode() == 200) {
                    try (InputStream in = c.getInputStream()) {
                        JSONObject o = new JSONObject(readAll(in));
                        String p = o.optString("model_path", "");
                        if (!p.isEmpty()) name = new File(p).getName();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
            final String n = name;
            ui.post(() -> {
                if (n != null && runningModelName == null) {
                    runningModelName = n;
                    updateStatus();
                }
            });
        });
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
        return b.toString("UTF-8");
    }

    private void updateStatus() {
        if (statusText == null) return;
        String model = runningModelName != null ? "  -  " + runningModelName : "";
        int color;
        String text;
        switch (state) {
            case READY:
                text = getString(R.string.status_ready) + model;
                color = Color.rgb(0x2E, 0x9E, 0x4F);
                break;
            case LOADING:
                text = getString(R.string.status_loading) + model;
                color = Color.rgb(0xF5, 0x9E, 0x0B);
                break;
            case STARTING:
                text = getString(R.string.status_starting) + model;
                color = Color.rgb(0xF5, 0x9E, 0x0B);
                break;
            case STOPPING:
                text = getString(R.string.status_stopping);
                color = Color.rgb(0xF5, 0x9E, 0x0B);
                break;
            case STOPPED:
                text = getString(R.string.status_stopped);
                color = Color.GRAY;
                runningModelName = null;
                break;
            default:
                text = getString(R.string.status_checking);
                color = Color.GRAY;
                break;
        }
        statusText.setText(text);
        statusText.setTextColor(color);
        boolean running = state == State.READY || state == State.LOADING || state == State.STARTING;
        btnStartStop.setText(running ? R.string.btn_stop : R.string.btn_start);
        btnStartStop.setEnabled(state != State.STOPPING && state != State.UNKNOWN);
        if (chatOverlay != null) {
            chatOverlay.setVisibility(state == State.READY ? View.GONE : View.VISIBLE);
            switch (state) {
                case LOADING:
                case STARTING:
                    chatOverlayText.setText(R.string.overlay_loading);
                    break;
                case STOPPING:
                    chatOverlayText.setText(R.string.overlay_stopping);
                    break;
                default:
                    chatOverlayText.setText(R.string.overlay_stopped);
                    break;
            }
        }
    }

    // =======================================================================================
    // Log
    // =======================================================================================

    private final Runnable logTask = new Runnable() {
        @Override
        public void run() {
            if (!resumed || page != PAGE_LOG) return;
            refreshLog();
            ui.postDelayed(this, 1500);
        }
    };

    private void startLogRefresh() {
        ui.removeCallbacks(logTask);
        ui.post(logTask);
    }

    private void refreshLog() {
        final String path = etLog.getText().toString().trim();
        io.execute(() -> {
            String t = readLogTail(path, 48 * 1024);
            ui.post(() -> {
                logText.setText(t.isEmpty() ? getString(R.string.log_empty, etLog.getText().toString()) : t);
                if (cbLogFollow.isChecked()) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            });
        });
    }

    /** Letzte maxBytes der Log-Datei, ohne Farbcodes und ohne angeschnittene erste Zeile. */
    private String readLogTail(String path, int maxBytes) {
        File f = new File(path);
        if (!f.isFile()) return "";
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long from = Math.max(0, len - maxBytes);
            byte[] buf = new byte[(int) (len - from)];
            raf.seek(from);
            raf.readFully(buf);
            String s = new String(buf, StandardCharsets.UTF_8);
            if (from > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);
            }
            return s.replaceAll("\u001B\\[[;\\d]*m", "");
        } catch (IOException e) {
            return Environment.isExternalStorageManager() ? "" : getString(R.string.log_no_access);
        }
    }

    private static String lastLines(String s, int n) {
        String[] lines = s.split("\n");
        int from = Math.max(0, lines.length - n);
        return String.join("\n", Arrays.copyOfRange(lines, from, lines.length)).trim();
    }

    // =======================================================================================
    // Dateiauswahl fuer die Web-UI (Bilder/Dateien anhaengen)
    // =======================================================================================

    private boolean openFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        Intent i = params.createIntent();
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(i, REQ_FILE);
            return true;
        } catch (ActivityNotFoundException e) {
            fileCallback = null;
            toast(getString(R.string.toast_no_file_app));
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] uris = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                uris = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) uris[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(uris);
        fileCallback = null;
    }
}
