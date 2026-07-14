package com.smsgateway.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";

    private static int getSlot(Context context, Intent intent) {
        int subId = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_ID", -2);
        if (subId == -2) subId = intent.getIntExtra("phoneId", -1);
        if (subId == -1) subId = intent.getIntExtra("sub_id", -1);
        if (subId == -1) {
            int slot = intent.getIntExtra("slot", -1);
            if (slot >= 0) return slot;
        }
        if (subId == -1) return -1;
        try {
            SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return -1;
            List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list == null) return -1;
            for (SubscriptionInfo info : list) {
                if (info.getSubscriptionId() == subId) return info.getSimSlotIndex();
            }
        } catch (Exception e) { Log.e(TAG, "getSlot", e); }
        return -1;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null) return;

        String format = bundle.getString("format");
        StringBuilder sb = new StringBuilder();
        String originatingAddress = null;
        int slot = getSlot(context, intent);

        for (Object pdu : pdus) {
            SmsMessage smsMessage = SmsMessage.createFromPdu((byte[]) pdu, format);
            if (smsMessage != null) {
                if (originatingAddress == null) originatingAddress = smsMessage.getOriginatingAddress();
                sb.append(smsMessage.getMessageBody());
            }
        }

        if (originatingAddress == null) return;

        final String number = originatingAddress;
        final String body = sb.toString();
        final int finalSlot = slot;

        // Keep CPU alive with WakeLock for background reliability
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsGateway:receiver");
        wl.acquire(30000);

        // Toast must be on main thread
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, "📩 收到短信: " + number, Toast.LENGTH_SHORT).show());

        final BroadcastReceiver.PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                reportDirect(context, number, body, finalSlot);
            } finally {
                pending.finish();
                if (wl.isHeld()) wl.release();
            }
        }).start();
    }

    private void reportDirect(Context ctx, String number, String body, int slot) {
        try {
            String url = Prefs.getServerUrl(ctx);
            String phoneId = Prefs.getPhoneId(ctx);
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

            // 单卡手机 slot=-1, 默认为卡1 (slot=0 → 存储为 1)
            int fixedSlot = slot >= 0 ? slot : 0;
            String json = new org.json.JSONObject()
                .put("phone_id", phoneId)
                .put("number", number)
                .put("body", body)
                .put("timestamp", ts)
                .put("type", "received")
                .put("slot", fixedSlot + 1)
                .toString();

            RequestBody rb = RequestBody.create(json, MediaType.parse("application/json"));
            Request rq = new Request.Builder().url(url + "/api/sms/receive").post(rb).build();

            OkHttpClient client = new OkHttpClient();
            Response r = client.newCall(rq).execute();
            r.close();
            Log.i(TAG, "SMS reported: " + number);
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, "✅ 已转发" + (slot >= 0 ? " [卡" + (slot + 1) + "]" : ""), Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            Log.e(TAG, "reportDirect IO", e);
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, "❌ 转发失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.e(TAG, "reportDirect", e);
        }
    }
}
