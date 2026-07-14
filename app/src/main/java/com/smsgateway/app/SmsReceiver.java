package com.smsgateway.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionInfo;
import android.util.Log;

import java.util.List;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";
    private static SmsGatewayService instance;

    @SuppressWarnings("unused")
    public SmsReceiver() {}

    public static void setService(SmsGatewayService service) {
        instance = service;
    }

    /**
     * 解析 SIM 卡槽位：
     *  - intent extra "slot" / "phoneId" / "sub_id" → subscription id
     *  - SubscriptionManager → simSlotIndex (0 或 1)
     */
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
                if (info.getSubscriptionId() == subId) {
                    return info.getSimSlotIndex(); // 0 = 卡1, 1 = 卡2
                }
            }
        } catch (Exception e) { Log.e(TAG, "getSlot err", e); }
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
                if (originatingAddress == null) {
                    originatingAddress = smsMessage.getOriginatingAddress();
                }
                sb.append(smsMessage.getMessageBody());
            }
        }

        if (originatingAddress != null && instance != null) {
            Log.i(TAG, "SMS from " + originatingAddress + " (卡" + (slot + 1) + ")");
            instance.onSmsReceived(originatingAddress, sb.toString(), slot);
        }
    }
}
