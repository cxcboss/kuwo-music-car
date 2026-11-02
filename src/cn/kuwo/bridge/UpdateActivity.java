package cn.kuwo.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Update UI only opens from About; no update request runs during cold start. */
public final class UpdateActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;
    private ProgressBar progress;
    private Button action;
    private GithubUpdater.Release release;
    private File verifiedApk;
    private boolean busy, waitingForStorage, waitingForInstaller;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        int padding=(int)(32*getResources().getDisplayMetrics().density);
        page.setPadding(padding,padding,padding,padding);
        page.setBackgroundColor(Color.rgb(19,22,29));
        TextView back=new TextView(this);
        back.setText("‹  关于");back.setTextSize(22);back.setTextColor(Color.WHITE);
        back.setPadding(0,0,0,padding);back.setOnClickListener(v -> finish());
        page.addView(back,new LinearLayout.LayoutParams(-1,-2));
        TextView title=new TextView(this);
        title.setText("酷我音乐");title.setTextSize(30);title.setTypeface(null,Typeface.BOLD);
        title.setTextColor(Color.WHITE);title.setGravity(Gravity.CENTER);
        page.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView version=new TextView(this);
        version.setText("V"+BuildInfo.VERSION_NAME);version.setTextSize(20);
        version.setTextColor(0xffa8afbc);version.setGravity(Gravity.CENTER);
        version.setPadding(0,padding/3,0,padding);
        page.addView(version,new LinearLayout.LayoutParams(-1,-2));
        status=new TextView(this);status.setTextSize(22);status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        page.addView(status,new LinearLayout.LayoutParams(-1,-2));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(-1,(int)(6*getResources().getDisplayMetrics().density));
        bar.topMargin=padding;bar.bottomMargin=padding;page.addView(progress,bar);
        action=new Button(this);action.setTextSize(20);action.setText("检查更新");
        LinearLayout.LayoutParams button=new LinearLayout.LayoutParams((int)(250*getResources().getDisplayMetrics().density),-2);
        button.topMargin=padding;page.addView(action,button);
        action.setOnClickListener(v -> {
            if(verifiedApk!=null) install();
            else if(release!=null && release.versionCode>BuildInfo.VERSION_CODE) downloadWithPermission();
            else check();
        });
        setContentView(page);
        check();
    }

    private void check() {
        if(busy) return;
        busy=true;action.setEnabled(false);status.setText("正在检查更新");
        worker.execute(() -> {
            try {
                GithubUpdater.Release result=GithubUpdater.latest();
                runOnUiThread(() -> {
                    if(isDestroyed()) return;
                    release=result;busy=false;action.setEnabled(true);
                    if(result.versionCode>BuildInfo.VERSION_CODE) {
                        status.setText("发现新版本 V"+result.version);action.setText("下载更新");
                    } else { status.setText("已是最新版本");action.setText("检查更新"); }
                });
            } catch(Exception e) { failed("检查失败",e); }
        });
    }

    private void downloadWithPermission() {
        if(Build.VERSION.SDK_INT>=30 && !Environment.isExternalStorageManager()) {
            waitingForStorage=true;
            Intent permission=new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName()));
            try { startActivity(permission); }
            catch(ActivityNotFoundException e) { waitingForStorage=false;status.setText("存储权限不可用"); }
        } else if(Build.VERSION.SDK_INT>=23 && Build.VERSION.SDK_INT<30
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},1);
        } else download();
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==1 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) download();
        else status.setText("未获得存储权限");
    }

    @Override protected void onResume() {
        super.onResume();
        if(waitingForStorage) {
            waitingForStorage=false;
            if(Build.VERSION.SDK_INT>=30 && Environment.isExternalStorageManager()) download();
            else status.setText("未获得存储权限");
        }
        if(waitingForInstaller) {
            waitingForInstaller=false;
            if(Build.VERSION.SDK_INT>=26 && getPackageManager().canRequestPackageInstalls()) install();
            else status.setText("未获得安装权限");
        }
    }

    private void download() {
        if(busy) return;
        busy=true;action.setEnabled(false);progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);status.setText("正在下载");
        worker.execute(() -> {
            File folder=new File(Environment.getExternalStorageDirectory(),"000/酷我音乐更新");
            File partial=new File(folder,release.fileName+".part");
            try {
                if(!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create update directory");
                File destination=new File(folder,release.fileName);
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                HttpURLConnection connection=GithubUpdater.connect(release.url);
                long total=0;
                int lastPercent=-1;
                try(InputStream input=connection.getInputStream(); FileOutputStream output=new FileOutputStream(partial)) {
                    byte[] buffer=new byte[65536];int count;
                    while((count=input.read(buffer))!=-1) {
                        if(Thread.currentThread().isInterrupted()) throw new IOException("Download cancelled");
                        total+=count;
                        if(total>release.size) throw new IOException("Unexpected APK size");
                        output.write(buffer,0,count);digest.update(buffer,0,count);
                        int percent=(int)(total*100/release.size);
                        if(percent!=lastPercent) {
                            lastPercent=percent;
                            runOnUiThread(() -> { if(!isDestroyed()) { progress.setProgress(percent);status.setText("正在下载 "+percent+"%"); }});
                        }
                    }
                    output.getFD().sync();
                } finally { connection.disconnect(); }
                StringBuilder hex=new StringBuilder();
                for(byte value:digest.digest()) hex.append(String.format(Locale.ROOT,"%02x",value&255));
                if(total!=release.size || !release.sha256.contentEquals(hex)) throw new IOException("APK checksum mismatch");
                PackageManager pm=getPackageManager();
                PackageInfo archive=pm.getPackageArchiveInfo(partial.getAbsolutePath(),PackageManager.GET_SIGNATURES);
                PackageInfo installed=pm.getPackageInfo(getPackageName(),PackageManager.GET_SIGNATURES);
                if(archive==null || !getPackageName().equals(archive.packageName)
                        || archive.versionCode!=release.versionCode || !release.version.equals(archive.versionName)
                        || !Arrays.equals(installed.signatures,archive.signatures)) throw new IOException("APK identity mismatch");
                if(destination.exists() && !destination.delete()) throw new IOException("Cannot replace saved APK");
                if(!partial.renameTo(destination)) throw new IOException("Cannot finalize APK");
                runOnUiThread(() -> {
                    if(isDestroyed()) return;
                    verifiedApk=destination;busy=false;progress.setVisibility(View.GONE);
                    status.setText("下载完成");action.setText("安装更新");action.setEnabled(true);install();
                });
            } catch(Exception e) {
                if(partial.exists()) partial.delete();
                failed("下载失败",e);
            }
        });
    }

    private void install() {
        if(Build.VERSION.SDK_INT>=26 && !getPackageManager().canRequestPackageInstalls()) {
            waitingForInstaller=true;
            try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()))); }
            catch(ActivityNotFoundException e) { waitingForInstaller=false;status.setText("安装权限不可用"); }
            return;
        }
        try {
            Uri uri=(Uri)Class.forName("androidx.core.content.FileProvider")
                    .getMethod("getUriForFile",android.content.Context.class,String.class,File.class)
                    .invoke(null,this,getPackageName()+".fileprovider",verifiedApk);
            Intent installer=new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,"application/vnd.android.package-archive");
            installer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installer.setClipData(ClipData.newRawUri("APK",uri));
            installer.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE,true);
            try { startActivity(installer); }
            catch(ActivityNotFoundException e) { startActivity(installer.setAction(Intent.ACTION_VIEW)); }
        } catch(Exception e) { status.setText("安装未完成");Log.w("KwUpdater","Saved APK: "+verifiedApk,e); }
    }

    private void failed(String message,Exception error) {
        Log.w("KwUpdater",message,error);
        runOnUiThread(() -> {
            if(isDestroyed()) return;
            busy=false;status.setText(message);action.setEnabled(true);progress.setVisibility(View.GONE);
        });
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
