package com.smsgateway.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final int SMS_PERMISSION_REQUEST = 100;
    private static final long STATUS_CHECK_INTERVAL = 3000;

    private EditText etServerUrl, etPhoneId;
    private TextView tvStatus;
    private ExecutorService executor;
    private OkHttpClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServerUrl = findViewById(R.id.et_server_url);
        etPhoneId = findViewById(R.id.et_phone_id);
        tvStatus = findViewById(R.id.tv_status);
        Button btnStart = findViewById(R.id.btn_start);

        client = new OkHttpClient();
        executor = Executors.newCachedThreadPool();

        etServerUrl.setText(Prefs.getServerUrl(this));
        String savedPhoneId = Prefs.getPhoneId(this);
        if (savedPhoneId.isEmpty() || "android_phone".equals(savedPhoneId)) {
            savedPhoneId = "手机_" + java.util.UUID.randomUUID().toString().substring(0, 4);
            Prefs.setPhoneId(this, savedPhoneId);
        }
        etPhoneId.setText(savedPhoneId);

        checkSmsPermissions();

        btnStart.setOnClickListener(v -> saveAndStart());

        // Auto-save on text change
        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            public void onTextChanged(CharSequence a, int b, int c, int d) {}
            public void afterTextChanged(Editable a) { savePref(); }
        };
        etServerUrl.addTextChangedListener(watcher);
        etPhoneId.addTextChangedListener(watcher);

        // Periodic status update
        tvStatus.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateStatus();
                tvStatus.postDelayed(this, STATUS_CHECK_INTERVAL);
            }
        }, 500);
    }

    private void savePref() {
        String url = etServerUrl.getText().toString().trim();
        String phoneId = etPhoneId.getText().toString().trim();
        if (!url.isEmpty()) Prefs.setServerUrl(this, url);
        if (!phoneId.isEmpty()) Prefs.setPhoneId(this, phoneId);
    }

    private void checkSmsPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] permissions = {
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    Manifest.permission.READ_PHONE_STATE
            };
            boolean anyDenied = false;
            for (String perm : permissions) {
                if (ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    anyDenied = true;
                    break;
                }
            }
            if (anyDenied) {
                ActivityCompat.requestPermissions(this, permissions, SMS_PERMISSION_REQUEST);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SMS_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                updateStatus();
            } else {
                tvStatus.setText("监听 ✗ | 服务 ✗ | 连接 ✗");
                Toast.makeText(this, "请授予短信权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void saveAndStart() {
        String url = etServerUrl.getText().toString().trim();
        String phoneId = etPhoneId.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (phoneId.isEmpty()) phoneId = "android_phone";
        Prefs.setServerUrl(this, url);
        Prefs.setPhoneId(this, phoneId);
        SmsGatewayService.start(this);
        updateStatus();
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void updateStatus() {
        // 监听: SMS permission granted?
        boolean hasSms = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        // 服务: started (we check if SmsGatewayService is alive via static flag)
        boolean isRunning = SmsGatewayService.isRunning;
        // 连接: test server
        executor.execute(() -> {
            boolean connected = false;
            try {
                String url = Prefs.getServerUrl(this);
                if (!url.isEmpty()) {
                    Response r = client.newCall(new Request.Builder().url(url + "/api/status").build()).execute();
                    connected = r.isSuccessful();
                    r.close();
                }
            } catch (IOException ignored) {}
            final boolean conn = connected;
            runOnUiThread(() -> {
                String status = String.format("监听 %s | 服务 %s | 连接 %s",
                        hasSms ? "✓" : "✗",
                        isRunning ? "✓" : "✗",
                        conn ? "✓" : "✗");
                tvStatus.setText(status);
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
