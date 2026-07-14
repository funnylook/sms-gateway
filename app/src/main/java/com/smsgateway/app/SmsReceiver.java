package com.smsgateway.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PendingResult;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

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

/**
 * SMS_RECEIVED broadcast receiver (AndroidManifest registration).
 *
 * Design rationale:
 *   - Context-registered receivers only work while the app process is alive. On Xiaomi /
 *     Huawei / OPPO / Vivo / Pixel-with-battery-saver, the process gets killed
 *     aggressively and the receiver stops receiving SMS.
 *   - Manifest-registered receivers are discovered by Android even if the process is dead
 *     and a fresh process is created to deliver the broadcast.
 *   - goAsync() keeps the broadcast "in-flight" so Android does not kill the process while
 *     the HTTP POST is on the wire.
 */
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

        // Toast必须在主线程
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, "📩 收到短信: " + number, Toast.LENGTH_SHORT).show());

        // 用goAsync保持进程存活，做网络IO
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                reportDirect(context, number, body, finalSlot);
            } finally {
                pending.finish();
            }
        }).start();
    }

    private void reportDirect(Context ctx, String number, String body, int slot) {
        try {
            String url = Prefs.getServerUrl(ctx);
            String phoneId = Prefs.getPhoneId(ctx);
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

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

            OkHttpClient client = new OkHttpClient();
            Response r = client.newCall(rq).execute();
            r.close();
            Log.i(TAG, "SMS reported: " + number);
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, "✅ 已转发" + (slot >= 0 ? " [卡" + (slot + 1) + "]" : ""), Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            Log.e(TAG, "report IO", e);
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, "❌ 转发失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.e(TAG, "report", e);
        }
    }
}
