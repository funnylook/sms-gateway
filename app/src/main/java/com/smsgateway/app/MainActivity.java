package com.smsgateway.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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
        Button btnSave = findViewById(R.id.btn_save);
        Button btnStart = findViewById(R.id.btn_start);
        Button btnTest = findViewById(R.id.btn_test);

        client = new OkHttpClient();
        executor = Executors.newCachedThreadPool();

        etServerUrl.setText(Prefs.getServerUrl(this));
        etPhoneId.setText(Prefs.getPhoneId(this));

        checkSmsPermissions();

        btnSave.setOnClickListener(v -> saveConfig());
        btnStart.setOnClickListener(v -> startGateway());
        btnTest.setOnClickListener(v -> testConnection());

        updateStatus("就绪");
    }

    private void checkSmsPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] permissions = {
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.RECEIVE_BOOT_COMPLETED
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
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "请授予短信权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void saveConfig() {
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
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        updateStatus("配置已保存，正在监听...");
    }

    private void startGateway() {
        SmsGatewayService.start(this);
        Toast.makeText(this, "网关服务已启动", Toast.LENGTH_SHORT).show();
        updateStatus("服务已启动");
    }

    private void testConnection() {
        String url = etServerUrl.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }
        updateStatus("正在测试连接...");
        executor.execute(() -> {
            try {
                Response response = client.newCall(new Request.Builder().url(url + "/api/status").build()).execute();
                runOnUiThread(() -> {
                    if (response.isSuccessful()) {
                        updateStatus("✅ 连接正常");
                        Toast.makeText(this, "连接成功", Toast.LENGTH_SHORT).show();
                    } else {
                        updateStatus("❌ 连接失败: " + response.code());
                    }
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    updateStatus("❌ 连接异常: " + e.getMessage());
                    Toast.makeText(this, "异常: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateStatus(String status) {
        tvStatus.setText(status);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
