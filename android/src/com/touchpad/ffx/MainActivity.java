package com.touchpad.ffx;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private SharedPreferences sp;
    private EditText host, port;
    private Button toggle;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("cfg", MODE_PRIVATE);
        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("触屏手柄");
        title.setTextSize(24);
        root.addView(title);

        host = new EditText(this);
        host.setHint("电脑 IP");
        host.setSingleLine(true);
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        host.setText(sp.getString("host", ""));
        root.addView(host, lp(d, 16));

        port = new EditText(this);
        port.setHint("端口");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(sp.getInt("port", 39500)));
        root.addView(port, lp(d, 4));

        RadioGroup prof = new RadioGroup(this);
        prof.setOrientation(RadioGroup.HORIZONTAL);
        String[] names = {"FF10", "崩铁"};
        for (int i = 0; i < names.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setId(1000 + i);
            prof.addView(rb);
        }
        prof.check(1000 + sp.getInt("profile", 0));
        prof.setOnCheckedChangeListener((g, id) -> {
            sp.edit().putInt("profile", id - 1000).apply();
            restartIfRunning();
        });
        root.addView(prof, lp(d, 16));

        root.addView(slider("按键大小", "scale", 70, 150, 100, "%"), lp(d, 16));
        root.addView(slider("视角灵敏度", "camSens", 30, 300, 100, "%"), lp(d, 8));

        CheckBox inv = new CheckBox(this);
        inv.setText("视角上下反转");
        inv.setChecked(sp.getBoolean("camInvertY", false));
        inv.setOnCheckedChangeListener((v, on) -> sp.edit().putBoolean("camInvertY", on).apply());
        root.addView(inv, lp(d, 4));

        CheckBox flo = new CheckBox(this);
        flo.setText("浮动摇杆");
        flo.setChecked(sp.getBoolean("floatingJoy", false));
        flo.setOnCheckedChangeListener((v, on) -> sp.edit().putBoolean("floatingJoy", on).apply());
        root.addView(flo, lp(d, 4));
        root.addView(slider("不透明度", "alpha", 20, 100, 75, "%"), lp(d, 8));

        toggle = new Button(this);
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle, lp(d, 24));

        Button battery = new Button(this);
        battery.setText("应用设置（省电策略 / 悬浮窗）");
        battery.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        root.addView(battery, lp(d, 8));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != 0) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        toggle.setText(OverlayService.running ? "关闭控制层" : "开启控制层");
    }

    private LinearLayout.LayoutParams lp(float d, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Math.round(topDp * d);
        return p;
    }

    private LinearLayout slider(String name, String key, int min, int max, int def, String unit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        int val = sp.getInt(key, def);
        label.setText(name + "  " + val + unit);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(val - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                label.setText(name + "  " + (p + min) + unit);
                sp.edit().putInt(key, p + min).apply();
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        row.addView(label);
        row.addView(bar);
        return row;
    }

    private void restartIfRunning() {
        if (!OverlayService.running) return;
        startForegroundService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_RELOAD));
        dirtySince = sp.getAll().hashCode();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // apply slider / checkbox changes when leaving the settings screen
        if (dirtySince != sp.getAll().hashCode()) restartIfRunning();
    }

    private int dirtySince;

    @Override
    protected void onPostResume() {
        super.onPostResume();
        dirtySince = sp.getAll().hashCode();
    }

    private void onToggle() {
        Intent svc = new Intent(this, OverlayService.class);
        if (OverlayService.running) {
            stopService(svc);
            OverlayService.running = false;
            refresh();
            return;
        }
        String h = host.getText().toString().trim();
        if (h.isEmpty()) {
            Toast.makeText(this, "先填电脑 IP", Toast.LENGTH_SHORT).show();
            return;
        }
        int p;
        try { p = Integer.parseInt(port.getText().toString().trim()); } catch (Exception e) { p = 39500; }
        sp.edit().putString("host", h).putInt("port", p).apply();

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请允许“显示悬浮窗”", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        startForegroundService(svc);
        OverlayService.running = true;
        dirtySince = sp.getAll().hashCode();
        refresh();
        moveTaskToBack(true);
    }
}
