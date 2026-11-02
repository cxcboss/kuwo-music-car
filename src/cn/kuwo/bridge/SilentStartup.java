package cn.kuwo.bridge;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

/** Prepare the original foreground music service, without activities or autoplay. */
public final class SilentStartup {
    private static final String PREFS="lean_startup";
    private static String lastReason="", lastStatus="IDLE";
    private static long requestedAt;
    private SilentStartup() {}
    static Context app() throws Exception {
        return (Application)ReflectAccess.method(Class.forName("cn.kuwo.application.App"),"getApplication").invoke(null);
    }
    static boolean consent() {
        try {
            Object app=ReflectAccess.method(Class.forName("cn.kuwo.kwmusiccar.KwApp"),"getInstance").invoke(null);
            return app!=null && (Boolean)BridgeProvider.invoke(app,"isAppPrivacyAgreed");
        } catch(Exception e) { return false; }
    }
    static boolean ready() {
        try { return (Boolean)ReflectAccess.method(Class.forName("cn.kuwo.service.MainService"),"q").invoke(null); }
        catch(Exception e) { return false; }
    }
    public static boolean enabled() {
        try { return app().getSharedPreferences(PREFS,0).getBoolean("enabled",true); }
        catch(Exception e) { return true; }
    }
    public static void setEnabled(boolean enabled) {
        try {
            if (!app().getSharedPreferences(PREFS,0).edit().putBoolean("enabled",enabled).commit())
                throw new IllegalStateException("startup preference write failed");
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public static void onReceive(Context context,Intent intent) {
        if (intent==null) return;
        String action=intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (enabled()) start(action);
    }
    static Bundle start(String reason) {
        Bundle b=BridgeProvider.reply("OK","");
        lastReason=reason;
        if (!consent()) { lastStatus="PRIVACY_REQUIRED";b.putString("status",lastStatus);return b; }
        if (ready()) { lastStatus="READY";b.putBoolean("accepted",true);b.putBoolean("stateConfirmed",true);return b; }
        long now=SystemClock.elapsedRealtime();
        if ("STARTING".equals(lastStatus) && now-requestedAt<15000) {
            b.putBoolean("accepted",true);b.putBoolean("stateConfirmed",false);return b;
        }
        requestedAt=now;lastStatus="STARTING";
        try {
            Context context=app();
            Intent service=new Intent().setClassName(context,"cn.kuwo.service.MainService");
            service.putExtra("isAutoPlay",false);
            if(Build.VERSION.SDK_INT>=26) context.startForegroundService(service);
            else context.startService(service);
            b.putBoolean("accepted",true);b.putBoolean("stateConfirmed",ready());
        } catch(Exception e) { lastStatus="START_BLOCKED";b.putString("status",lastStatus);b.putBoolean("accepted",false); }
        return b;
    }
    static Bundle status() {
        Bundle b=new Bundle();
        b.putBoolean("autoStartEnabled",enabled());b.putBoolean("silent",true);b.putBoolean("autoPlay",false);
        b.putBoolean("privacyAgreed",consent());b.putBoolean("serviceReady",ready());
        String status=ready()?"READY":lastStatus;
        if ("STARTING".equals(status) && SystemClock.elapsedRealtime()-requestedAt>=15000) status="NOT_READY";
        b.putString("startupStatus",status);b.putString("lastReason",lastReason);b.putLong("requestedAtElapsedMs",requestedAt);
        return b;
    }
    public static void promote(Service service) {
        String channel="kuwo_music_background";
        NotificationManager manager=(NotificationManager)service.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT>=26) {
            NotificationChannel c=new NotificationChannel(channel,"酷我音乐",NotificationManager.IMPORTANCE_LOW);
            c.setSound(null,null);c.enableVibration(false);c.setShowBadge(false);manager.createNotificationChannel(c);
            b=new Notification.Builder(service,channel);
        } else b=new Notification.Builder(service).setPriority(Notification.PRIORITY_LOW);
        int icon=service.getResources().getIdentifier("app_logo","drawable",service.getPackageName());
        if (icon==0) icon=service.getApplicationInfo().icon;
        b.setSmallIcon(icon).setContentTitle("酷我音乐").setOngoing(true).setOnlyAlertOnce(true).setSound(null);
        Intent launch=service.getPackageManager().getLaunchIntentForPackage(service.getPackageName());
        if (launch!=null) b.setContentIntent(PendingIntent.getActivity(service,0,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        Notification notification=b.build();
        if (Build.VERSION.SDK_INT>=29) service.startForeground(0x3fffffff,notification,2);
        else service.startForeground(0x3fffffff,notification);
    }
}
