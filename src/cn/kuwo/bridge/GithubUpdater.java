package cn.kuwo.bridge;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import org.json.JSONObject;

/** Public GitHub Releases are the sole source for APK updates. */
public final class GithubUpdater {
    private GithubUpdater() {}

    public static void bindAbout(View root) {
        Context context=root.getContext();
        String pkg=context.getPackageName();
        TextView version=root.findViewById(context.getResources().getIdentifier("version","id",pkg));
        TextView update=root.findViewById(context.getResources().getIdentifier("check_update","id",pkg));
        version.setText(BuildInfo.VERSION_NAME);
        version.setOnClickListener(null);
        version.setClickable(false);
        root.findViewById(context.getResources().getIdentifier("text_title","id",pkg)).setVisibility(View.GONE);
        update.setVisibility(View.VISIBLE);
        update.setText("检查更新");
        update.setOnClickListener(v -> context.startActivity(new Intent(context,UpdateActivity.class)));
    }

    static final class Release {
        final String version, fileName, sha256;
        final int versionCode;
        final long size;
        final URL url;

        Release(String json) throws Exception {
            JSONObject data=new JSONObject(json);
            version=data.getString("versionName");
            versionCode=data.getInt("versionCode");
            fileName=data.getString("fileName");
            sha256=data.getString("sha256");
            size=data.getLong("size");
            URI uri=new URI(data.getString("downloadUrl"));
            if(data.getInt("schemaVersion")!=1 || !BuildInfo.APPLICATION_ID.equals(data.getString("applicationId"))
                    || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") || versionCode<=0 || size<=0
                    || !fileName.equals(version+"-酷我音乐.apk") || !sha256.matches("[0-9a-f]{64}")
                    || !"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || !uri.getPath().equals("/"+BuildInfo.REPOSITORY+"/releases/download/v"+version+"/"+version+"-kuwo-music.apk")) {
                throw new IOException("Invalid release metadata");
            }
            url=uri.toURL();
        }
    }

    static HttpURLConnection connect(URL url) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent","KuwoMusic/"+BuildInfo.VERSION_NAME);
        connection.setRequestProperty("Accept-Encoding","identity");
        connection.setUseCaches(false);
        int code=connection.getResponseCode();
        if(code!=200) {
            connection.disconnect();
            throw new IOException("HTTP "+code);
        }
        return connection;
    }

    static Release latest() throws Exception {
        URL url=new URL("https://github.com/"+BuildInfo.REPOSITORY+"/releases/latest/download/update.json");
        HttpURLConnection connection=connect(url);
        try(InputStream stream=connection.getInputStream(); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];
            int count;
            while((count=stream.read(buffer))!=-1) {
                output.write(buffer,0,count);
                if(output.size()>65536) throw new IOException("Release metadata too large");
            }
            return new Release(output.toString("UTF-8"));
        } finally { connection.disconnect(); }
    }
}
