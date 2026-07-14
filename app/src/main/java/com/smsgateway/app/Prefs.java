package com.smsgateway.app;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static final String PREF_NAME = "sms_gateway";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_PHONE_ID = "phone_id";

    public static String getServerUrl(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_URL, "");
    }

    public static void setServerUrl(Context context, String url) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SERVER_URL, url).apply();
    }

    public static String getPhoneId(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PHONE_ID, "android_phone");
    }

    public static void setPhoneId(Context context, String id) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PHONE_ID, id).apply();
    }
}
