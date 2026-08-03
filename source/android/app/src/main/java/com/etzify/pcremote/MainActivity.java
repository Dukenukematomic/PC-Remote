package com.etzify.pcremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Two screens: pick a PC that was found on the network, then drive it.
 *
 * The last PC used is remembered, and reconnecting to it is attempted as soon
 * as the app opens, so the usual case is opening the app straight onto the
 * trackpad.
 */
public class MainActivity extends Activity implements RemoteClient.Listener {

    private static final String PREFS = "pc_remote";
    private static final String KEY_HOST = "last_host";
    private static final String KEY_PORT = "last_port";
    private static final String KEY_NAME = "last_name";
    private static final String KEY_SCREEN = "last_screen_port";

    private static final int SCREEN_PICKER = 0;
    private static final int SCREEN_REMOTE = 1;

    private final Discovery discovery = new Discovery();
    private final RemoteClient client = new RemoteClient();
    private final List<Discovery.Pc> pcs = new ArrayList<>();

    private ViewFlipper flipper;
    private ListView list;
    private TextView scanStatus;
    private LinearLayout emptyHint;
    private TextView connectedName;
    private TextView connectedState;
    private TrackpadView trackpad;
    private PcAdapter adapter;

    private View keyboardPanel;
    private View mediaControls;
    private View stage;
    private ImageButton keyboardToggle;
    private EditText keyInput;
    private boolean keyboardMode;

    private ScreenView screenView;
    private ImageButton screenToggle;
    private View monitorTabsScroll;
    private LinearLayout monitorTabs;
    private final ScreenStream screenStream = new ScreenStream();
    private final List<ScreenStream.Monitor> monitorList = new ArrayList<>();
    private boolean screenMode;
    private int currentMonitor;
    private boolean suppressWatcher;
    private String typedSoFar = "";

    private SharedPreferences prefs;
    private String pendingName;
    private boolean autoConnecting;
    /** The PC of the current session, so leaving it can re-list it at once. */
    private Discovery.Pc connectedPc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        flipper = findViewById(R.id.flipper);
        list = findViewById(R.id.pc_list);
        scanStatus = findViewById(R.id.scan_status);
        emptyHint = findViewById(R.id.empty_hint);
        connectedName = findViewById(R.id.connected_name);
        connectedState = findViewById(R.id.connected_state);
        trackpad = findViewById(R.id.trackpad);
        keyboardPanel = findViewById(R.id.keyboard_panel);
        mediaControls = findViewById(R.id.media_controls);
        stage = findViewById(R.id.stage);
        keyboardToggle = findViewById(R.id.btn_keyboard);
        keyInput = findViewById(R.id.key_input);
        screenView = findViewById(R.id.screen_view);
        screenToggle = findViewById(R.id.btn_screen);
        monitorTabsScroll = findViewById(R.id.monitor_tabs_scroll);
        monitorTabs = findViewById(R.id.monitor_tabs);

        trackpad.setClient(client);
        // The picture sits at the top of the stage and the trackpad covers the
        // whole of it, so the card has to start wherever the picture ends.
        screenView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or_, int ob) {
                if (screenMode) trackpad.setCardTop(b);
            }
        });
        screenView.setRecycler(new ScreenView.FrameRecycler() {
            @Override
            public void release(android.graphics.Bitmap frame) {
                screenStream.release(frame);
            }
        });
        wireKeyboard();

        adapter = new PcAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                connectTo(pcs.get(pos));
            }
        });

        findViewById(R.id.btn_rescan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartDiscovery();
            }
        });

        findViewById(R.id.btn_manual).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askForIp();
            }
        });

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backToPicker();
            }
        });

        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        });

        keyboardToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setKeyboardMode(!keyboardMode);
            }
        });

        screenToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setScreenMode(!screenMode);
            }
        });

        Settings.apply(prefs, trackpad);
        wireRemoteButtons();
        showEmptyState();
    }

    private void wireRemoteButtons() {
        findViewById(R.id.btn_left_click).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.click("left");
            }
        });
        findViewById(R.id.btn_right_click).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.click("right");
            }
        });

        mediaButton(R.id.btn_prev, "prev");
        mediaButton(R.id.btn_play, "play");
        mediaButton(R.id.btn_next, "next");
        mediaButton(R.id.btn_vol_down, "voldown");
        mediaButton(R.id.btn_mute, "mute");
        mediaButton(R.id.btn_vol_up, "volup");
    }

    // -- screen mode -------------------------------------------------------

    /**
     * Puts the PC's screen behind the trackpad. The trackpad keeps every
     * gesture and simply stops painting its own card, so a swipe now happens
     * over a picture of the pointer it is moving.
     */
    private void setScreenMode(boolean on) {
        if (on && (connectedPc == null || connectedPc.screenPort <= 0)) {
            Toast.makeText(this, R.string.screen_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        screenMode = on;
        screenView.setVisibility(on ? View.VISIBLE : View.GONE);
        trackpad.setTransparent(on);
        updateMonitorTabs();
        screenToggle.setBackgroundResource(on ? R.drawable.bg_button_accent
                : R.drawable.bg_button);

        if (!on) trackpad.setCardTop(0);

        if (on) {
            screenView.setStatus(getString(R.string.screen_connecting));
            startScreenStream(currentMonitor);
            screenStream.fetchMonitors(new ScreenStream.MonitorsListener() {
                @Override
                public void onMonitors(List<ScreenStream.Monitor> monitors) {
                    buildMonitorTabs(monitors);
                }
            });
        } else {
            screenStream.stop();
            screenView.clear();
        }
    }

    private void startScreenStream(int monitor) {
        if (connectedPc == null || connectedPc.screenPort <= 0) return;
        currentMonitor = monitor;

        // Ask for roughly the phone's own width; anything more is detail the
        // screen cannot show and bandwidth the Wi-Fi has to carry.
        int requested = Math.max(480, Math.min(1280,
                getResources().getDisplayMetrics().widthPixels));
        screenStream.configure(connectedPc.host, connectedPc.screenPort,
                requested);
        screenStream.start(monitor, new ScreenStream.Listener() {
            @Override
            public void onFrame(android.graphics.Bitmap frame) {
                if (screenMode) {
                    screenView.setFrame(frame);
                } else {
                    screenStream.release(frame);
                }
            }

            @Override
            public void onState(String message, boolean error) {
                if (screenMode) screenView.setStatus(message);
            }
        });
    }

    /** One tab per display, plus "All screens" when there is more than one. */
    private void buildMonitorTabs(List<ScreenStream.Monitor> monitors) {
        monitorTabs.removeAllViews();
        monitorList.clear();
        monitorList.addAll(monitors);
        if (monitors.size() < 2) {
            updateMonitorTabs();
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        int padH = (int) (16 * density);
        int padV = (int) (8 * density);
        int gap = (int) (8 * density);

        for (final ScreenStream.Monitor monitor : monitors) {
            TextView tab = new TextView(this);
            tab.setText(monitor.name);
            tab.setTextSize(13f);
            tab.setTextColor(getResources().getColor(R.color.text));
            tab.setPadding(padH, padV, padH, padV);
            tab.setBackgroundResource(R.drawable.bg_tab);
            tab.setSelected(monitor.id == currentMonitor);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(gap);
            tab.setLayoutParams(lp);

            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (monitor.id == currentMonitor) return;
                    selectMonitor(monitor.id);
                }
            });
            monitorTabs.addView(tab);
        }

        updateMonitorTabs();
    }

    private void updateMonitorTabs() {
        boolean show = screenMode && !keyboardMode
                && monitorTabs.getChildCount() > 1;
        monitorTabsScroll.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void selectMonitor(int id) {
        currentMonitor = id;
        // Tabs sit in the order the server listed them, which is not
        // necessarily the same as the ids themselves.
        for (int i = 0; i < monitorTabs.getChildCount() && i < monitorList.size(); i++) {
            monitorTabs.getChildAt(i)
                    .setSelected(monitorList.get(i).id == id);
        }
        screenStream.stop();
        screenView.clear();
        screenView.setStatus(getString(R.string.screen_connecting));
        startScreenStream(id);
    }

    // -- keyboard mode -----------------------------------------------------

    private void wireKeyboard() {
        keyButton(R.id.key_esc, "esc");
        keyButton(R.id.key_tab, "tab");
        keyButton(R.id.key_backspace, "backspace");
        keyButton(R.id.key_delete, "delete");

        // Enter also empties the box, so it reads like a fresh line each time.
        findViewById(R.id.key_enter).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.key("enter");
                clearInputBuffer();
            }
        });

        keyInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressWatcher) return;
                sendDifference(s.toString());
            }
        });
    }

    private void keyButton(int id, final String name) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.key(name);
                // The PC's caret moved, so our local buffer no longer mirrors
                // it; start tracking again from whatever is in the box.
                if ("backspace".equals(name)) {
                    trimInputBuffer();
                } else {
                    clearInputBuffer();
                }
            }
        });
    }

    /**
     * Mirrors an edit onto the PC. Comparing against what we last sent means a
     * correction or a swipe-typed replacement turns into the right number of
     * backspaces followed by the new text, instead of duplicated characters.
     */
    private void sendDifference(String now) {
        String prev = typedSoFar;
        int common = 0;
        int max = Math.min(now.length(), prev.length());
        while (common < max && now.charAt(common) == prev.charAt(common)) {
            common++;
        }
        for (int i = prev.length(); i > common; i--) {
            client.key("backspace");
        }
        if (now.length() > common) {
            client.typeText(now.substring(common));
        }
        typedSoFar = now;
    }

    private void clearInputBuffer() {
        suppressWatcher = true;
        keyInput.setText("");
        suppressWatcher = false;
        typedSoFar = "";
    }

    /** Drops the last character locally after a manual backspace key. */
    private void trimInputBuffer() {
        if (typedSoFar.isEmpty()) return;
        suppressWatcher = true;
        String next = typedSoFar.substring(0, typedSoFar.length() - 1);
        keyInput.setText(next);
        keyInput.setSelection(next.length());
        suppressWatcher = false;
        typedSoFar = next;
    }

    /**
     * The keyboard takes the place of the media controls and nothing else.
     * The trackpad, the mouse buttons and the screen all stay where they are;
     * the trackpad simply gives up height, since it is the only thing on the
     * screen that can be any size at all.
     */
    private void setKeyboardMode(boolean on) {
        keyboardMode = on;
        keyboardPanel.setVisibility(on ? View.VISIBLE : View.GONE);
        mediaControls.setVisibility(on ? View.GONE : View.VISIBLE);
        // With the soft keyboard up as well there is very little height left,
        // and picking a monitor is not something you do mid-sentence.
        updateMonitorTabs();
        keyboardToggle.setImageResource(on ? R.drawable.ic_mouse
                : R.drawable.ic_keyboard);
        keyboardToggle.setBackgroundResource(on ? R.drawable.bg_button_accent
                : R.drawable.bg_button);
        keyboardToggle.setContentDescription(
                getString(on ? R.string.trackpad_mode : R.string.keyboard_mode));

        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (on) {
            clearInputBuffer();
            keyInput.requestFocus();
            if (imm != null) imm.showSoftInput(keyInput, InputMethodManager.SHOW_IMPLICIT);
        } else {
            if (imm != null) {
                imm.hideSoftInputFromWindow(keyInput.getWindowToken(), 0);
            }
            keyInput.clearFocus();
        }
    }

    private void mediaButton(int id, final String key) {
        ImageButton b = findViewById(id);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.media(key);
            }
        });
    }

    // -- lifecycle ---------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        if (flipper.getDisplayedChild() == SCREEN_PICKER) {
            restartDiscovery();
            tryReconnectToLastPc();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        discovery.stop();
        screenStream.stop();
        client.disconnect();
        keepScreenOn(false);
        if (flipper.getDisplayedChild() == SCREEN_REMOTE) {
            setKeyboardMode(false);
            setScreenMode(false);
            flipper.setDisplayedChild(SCREEN_PICKER);
        }
    }

    @Override
    public void onBackPressed() {
        if (flipper.getDisplayedChild() != SCREEN_REMOTE) {
            super.onBackPressed();
        } else if (keyboardMode) {
            setKeyboardMode(false);   // first back leaves typing, not the PC
        } else if (screenMode) {
            setScreenMode(false);     // likewise: stop watching before leaving
        } else {
            backToPicker();
        }
    }

    // -- discovery ---------------------------------------------------------

    private void restartDiscovery() {
        discovery.stop();
        pcs.clear();
        adapter.notifyDataSetChanged();
        scanStatus.setText(R.string.searching);
        showEmptyState();

        discovery.start(new Discovery.Listener() {
            @Override
            public void onPcsFound(List<Discovery.Pc> found) {
                for (Discovery.Pc pc : found) mergePc(pc);
                refreshList();
            }
        });
    }

    /**
     * Adds a PC, or upgrades one we already listed.
     *
     * Two entries for the same host are equal even if one of them does not
     * know the screen port yet, so a seeded entry must give way to a real
     * discovery reply rather than silently keeping the gap.
     */
    private void mergePc(Discovery.Pc pc) {
        int at = pcs.indexOf(pc);
        if (at < 0) {
            pcs.add(pc);
        } else if (pc.screenPort > 0 && pcs.get(at).screenPort <= 0) {
            pcs.set(at, pc);
        }
    }

    /** Lists a PC we already know about without waiting for the next probe. */
    private void addPc(Discovery.Pc pc) {
        if (pc == null) return;
        mergePc(pc);
        refreshList();
    }

    private void refreshList() {
        sortLastUsedFirst();
        adapter.notifyDataSetChanged();
        scanStatus.setText(pcs.size() == 1
                ? getString(R.string.found_one)
                : getString(R.string.found_many, pcs.size()));
        showEmptyState();
    }

    private void sortLastUsedFirst() {
        final String lastHost = prefs.getString(KEY_HOST, null);
        if (lastHost == null) return;
        Collections.sort(pcs, new Comparator<Discovery.Pc>() {
            @Override
            public int compare(Discovery.Pc a, Discovery.Pc b) {
                int ra = lastHost.equals(a.host) ? 0 : 1;
                int rb = lastHost.equals(b.host) ? 0 : 1;
                return ra - rb;
            }
        });
    }

    private void showEmptyState() {
        boolean empty = pcs.isEmpty();
        emptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // -- connecting --------------------------------------------------------

    /** Silently re-opens the last connection so the app lands on the trackpad. */
    private void tryReconnectToLastPc() {
        String host = prefs.getString(KEY_HOST, null);
        if (host == null || client.isConnected()) return;
        autoConnecting = true;
        int port = prefs.getInt(KEY_PORT, RemoteClient.DEFAULT_PORT);
        pendingName = prefs.getString(KEY_NAME, host);
        connectedPc = new Discovery.Pc(pendingName, host, port,
                prefs.getInt(KEY_SCREEN, 0));
        client.connect(host, port, this);
    }

    private void connectTo(Discovery.Pc pc) {
        autoConnecting = false;
        pendingName = pc.name;
        connectedPc = pc;
        scanStatus.setText(getString(R.string.connecting, pc.name));
        prefs.edit()
                .putString(KEY_HOST, pc.host)
                .putInt(KEY_PORT, pc.port)
                .putString(KEY_NAME, pc.name)
                .putInt(KEY_SCREEN, pc.screenPort)
                .apply();
        client.connect(pc.host, pc.port, this);
    }

    private void backToPicker() {
        Discovery.Pc leaving = connectedPc;
        setKeyboardMode(false);
        setScreenMode(false);
        client.disconnect();
        keepScreenOn(false);
        flipper.setDisplayedChild(SCREEN_PICKER);
        restartDiscovery();
        // We were talking to it a moment ago, so list it now rather than
        // leaving the screen empty until the next probe comes back.
        addPc(leaving);
    }

    @Override
    public void onConnected(String host) {
        autoConnecting = false;
        discovery.stop();
        connectedName.setText(pendingName != null ? pendingName : host);
        connectedState.setText(R.string.connected);
        // Corrected a moment later by the welcome, whatever we guessed here.
        screenToggle.setVisibility(
                connectedPc != null && connectedPc.screenPort > 0
                        ? View.VISIBLE : View.GONE);
        flipper.setDisplayedChild(SCREEN_REMOTE);
        keepScreenOn(true);
    }

    /**
     * The server's own account of itself, which arrives on every connection
     * however it started. Discovery may never have been consulted -- a saved
     * PC reconnected at launch, or an address typed by hand -- so this is
     * what actually establishes whether the screen can be watched.
     */
    @Override
    public void onServerInfo(String hostName, int screenPort) {
        if (connectedPc == null) return;
        if (screenPort == connectedPc.screenPort) return;

        connectedPc = new Discovery.Pc(connectedPc.name, connectedPc.host,
                connectedPc.port, screenPort);
        prefs.edit().putInt(KEY_SCREEN, screenPort).apply();
        screenToggle.setVisibility(screenPort > 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDisconnected(String reason) {
        keepScreenOn(false);
        screenStream.stop();
        screenMode = false;
        boolean wasAuto = autoConnecting;
        autoConnecting = false;

        if (flipper.getDisplayedChild() == SCREEN_REMOTE) {
            flipper.setDisplayedChild(SCREEN_PICKER);
            restartDiscovery();
        }
        // A failed silent reconnect is not worth interrupting anyone over;
        // the list is already on screen.
        if (!wasAuto) {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show();
            scanStatus.setText(R.string.searching);
        }
    }

    private void keepScreenOn(boolean on) {
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // -- settings ----------------------------------------------------------

    /**
     * Sensitivity and scroll speed, applied to the trackpad as the sliders
     * move so the effect can be felt without closing the dialog first.
     */
    private void openSettings() {
        View view = LayoutInflater.from(this)
                .inflate(R.layout.dialog_settings, null);

        final SeekBar sensBar = view.findViewById(R.id.sens_bar);
        final SeekBar scrollBar = view.findViewById(R.id.scroll_bar);
        final TextView sensValue = view.findViewById(R.id.sens_value);
        final TextView scrollValue = view.findViewById(R.id.scroll_value);

        sensBar.setProgress(Settings.sensitivityProgress(prefs));
        scrollBar.setProgress(Settings.scrollSpeedProgress(prefs));
        sensValue.setText(Settings.format(Settings.sensitivity(sensBar.getProgress())));
        scrollValue.setText(Settings.format(Settings.scrollSpeed(scrollBar.getProgress())));

        sensBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float value = Settings.sensitivity(progress);
                sensValue.setText(Settings.format(value));
                trackpad.setSensitivity(value);
                prefs.edit().putInt(Settings.KEY_SENSITIVITY, progress).apply();
            }
        });

        scrollBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float value = Settings.scrollSpeed(progress);
                scrollValue.setText(Settings.format(value));
                trackpad.setScrollSpeed(value);
                prefs.edit().putInt(Settings.KEY_SCROLL_SPEED, progress).apply();
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(view)
                .setPositiveButton(R.string.done, null)
                .setNeutralButton(R.string.reset, (dialog, which) -> {
                    sensBar.setProgress(Settings.DEFAULT_SENSITIVITY);
                    scrollBar.setProgress(Settings.DEFAULT_SCROLL_SPEED);
                    prefs.edit()
                            .putInt(Settings.KEY_SENSITIVITY, Settings.DEFAULT_SENSITIVITY)
                            .putInt(Settings.KEY_SCROLL_SPEED, Settings.DEFAULT_SCROLL_SPEED)
                            .apply();
                    Settings.apply(prefs, trackpad);
                })
                .show();
    }

    /** SeekBar.OnSeekBarChangeListener with the two unused callbacks stubbed. */
    private abstract static class SimpleSeekBarListener
            implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    /** Fallback for networks where broadcast discovery is blocked. */
    private void askForIp() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.manual_hint);
        input.setText(prefs.getString(KEY_HOST, ""));

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.manual_title)
                .setView(wrap)
                .setPositiveButton(R.string.connect,
                        (dialog, which) -> {
                            String host = input.getText().toString().trim();
                            if (!host.isEmpty()) {
                                // Typed by hand, so assume the standard ports.
                                connectTo(new Discovery.Pc(host, host,
                                        RemoteClient.DEFAULT_PORT,
                                        ScreenStream.DEFAULT_SCREEN_PORT));
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // -- list adapter ------------------------------------------------------

    private class PcAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return pcs.size();
        }

        @Override
        public Object getItem(int position) {
            return pcs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_pc, parent, false);
            }

            Discovery.Pc pc = pcs.get(position);
            ((TextView) row.findViewById(R.id.pc_name)).setText(pc.name);
            ((TextView) row.findViewById(R.id.pc_host)).setText(pc.host);

            boolean lastUsed = pc.host.equals(prefs.getString(KEY_HOST, null));
            row.findViewById(R.id.pc_badge)
                    .setVisibility(lastUsed ? View.VISIBLE : View.GONE);

            return row;
        }
    }
}
