package com.smsgateway.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SmsGatewayService extends Service {
    private static final String TAG = "SmsGateway";
    private static final String CHANNEL_ID = "sms_gateway_channel";
    private static final int NOTIF_ID = 1001;

    private OkHttpClient client;
    private ExecutorService executor;
    private Handler handler;
    private volatile boolean running;

    public static void start(Context c) {
        Intent i = new Intent(c, SmsGatewayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        client = new OkHttpClient();
        executor = Executors.newFixedThreadPool(2);
        handler = new Handler(Looper.getMainLooper());
        createChannel();
        // 不再动态注册 SmsReceiver；改为 manifest 声明
        running = true;
        startPolling();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("短信网关").setContentText("正在监听短信…")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW).build();
        startForeground(NOTIF_ID, n);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        executor.shutdown();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent i) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "短信网关", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private void startPolling() {
        executor.execute(() -> {
            while (running) {
                try { poll(); Thread.sleep(5000); }
                catch (InterruptedException e) { break; }
                catch (Exception e) { Log.e(TAG, "poll err", e); }
            }
        });
    }

    private void poll() throws IOException, org.json.JSONException {
        String url = Prefs.getServerUrl(this);
        String phoneId = Prefs.getPhoneId(this);
        Response r = client.newCall(new Request.Builder()
                .url(url + "/api/sms/pending?phone_id=" + java.net.URLEncoder.encode(phoneId, "UTF-8")).get().build()).execute();
        if (!r.isSuccessful()) return;
        JSONObject root = new JSONObject(r.body().string());
        r.close();

        JSONArray cmds = root.getJSONArray("commands");
        for (int i = 0; i < cmds.length(); i++) {
            JSONObject cmd = cmds.getJSONObject(i);
            int slot = cmd.optInt("slot", -1);
            boolean ok = sendSms(cmd.getString("number"), cmd.getString("message"), slot);
            reportDone(cmd.getInt("id"), ok);
        }
    }

    private void reportDone(int id, boolean ok) {
        String url = Prefs.getServerUrl(this);
        try {
            String json = new JSONObject()
                .put("cmd_id", id)
                .put("status", ok ? "success" : "failed")
                .put("result", "" + (ok ? "sent" : "failed"))
                .toString();
            RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));
            Request rq = new Request.Builder().url(url + "/api/sms/done").post(rb).build();
            client.newCall(rq).enqueue(new Callback() {
                public void onFailure(Call c, IOException e) { Log.e(TAG, "report fail", e); }
                public void onResponse(Call c, Response r) { r.close(); }
            });
        } catch (Exception e) { Log.e(TAG, "reportDone err", e); }
    }

    public void onSmsReceived(String number, String body, int slot) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        reportSms(number, body, ts, slot);
    }

    private void reportSms(String number, String body, String ts, int slot) {
        String url = Prefs.getServerUrl(this);
        String phoneId = Prefs.getPhoneId(this);
        try {
            String json = new JSONObject()
                .put("phone_id", phoneId)
                .put("number", number)
                .put("body", body)
                .put("timestamp", ts)
                .put("type", "received")
                .put("slot", slot >= 0 ? slot + 1 : 0)
                .toString();
            RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));
            Request rq = new Request.Builder().url(url + "/api/sms/receive").post(rb).build();
            client.newCall(rq).enqueue(new Callback() {
                public void onFailure(Call c, IOException e) { Log.e(TAG, "report fail", e); }
                public void onResponse(Call c, Response r) { r.close(); Log.i(TAG, "SMS reported"); }
            });
        } catch (Exception e) { Log.e(TAG, "reportSms err", e); }
    }

    private boolean sendSms(String number, String message, int slot) {
        try {
            SmsManager sm = SmsManager.getDefault();
            if (slot >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SubscriptionManager subManager = (SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (subManager != null) {
                    List<SubscriptionInfo> subs = subManager.getActiveSubscriptionInfoList();
                    if (subs != null) {
                        for (SubscriptionInfo info : subs) {
                            if (info.getSimSlotIndex() == slot) {
                                sm = SmsManager.getSmsManagerForSubscriptionId(info.getSubscriptionId());
                                break;
                            }
                        }
                    }
                }
            }
            sm.sendTextMessage(number, null, message, null, null);
            return true;
        } catch (Exception e) { Log.e(TAG, "send fail", e); return false; }
    }
}
