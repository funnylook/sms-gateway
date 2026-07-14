package com.smsgateway.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";
    private final SmsGatewayService service;

    public SmsReceiver(SmsGatewayService service) {
        this.service = service;
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

        for (Object pdu : pdus) {
            SmsMessage smsMessage = SmsMessage.createFromPdu((byte[]) pdu, format);
            if (smsMessage != null) {
                if (originatingAddress == null) {
                    originatingAddress = smsMessage.getOriginatingAddress();
                }
                sb.append(smsMessage.getMessageBody());
            }
        }

        if (originatingAddress != null && service != null) {
            Log.i(TAG, "SMS from " + originatingAddress);
            service.onSmsReceived(originatingAddress, sb.toString());
        }
    }
}
