package com.newgame.downloader;

import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import java.io.File;

import static android.os.SystemClock.uptimeMillis;


public class MainActivity extends ActionBarActivity implements Downloader.DownloaderObserver {
    private final static String TAG = "gamecenter";

    private Timer timer;
    private Timer dequeueTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        final String url = "http://www.whatsapp.com/android/current/WhatsApp.apk";
        final String md5 = "";
        //final String url = "http://api.newgamepad.com/v1/clients/1532.17/tdzmClientAP.apk";
        //final String md5 = "995ef568b46cdbda298f328fbe92ed1f";

        timer = new Timer() {
            @Override
            protected void fire() {
                if (Downloader.getInstance().isInQueue(url)) {
                    Log.i(TAG, "progress:" + Downloader.getInstance().getProgress(url) + " speed:" + Downloader.getInstance().getSpeed(url));
                }
            }
        };

        timer.setTimer(uptimeMillis() + 1000, 1000);
        timer.resume();


        dequeueTimer = new Timer() {
            @Override
            protected void fire() {
                Downloader.getInstance().dequeue(url);
                File f = new File(MainActivity.this.getExternalFilesDir(null), "test.apk.part");
                String path = f.getAbsolutePath();
                Downloader.getInstance().enqueue(url, path, 0, md5);
            }
        };
        dequeueTimer.setTimer(uptimeMillis() + 10*1000);
        dequeueTimer.resume();

        Downloader.getInstance().addObserver(this);
        File f = new File(this.getExternalFilesDir(null), "test.apk.part");
        String path = f.getAbsolutePath();
        Downloader.getInstance().enqueue(url, path, 0, md5);
    }


    public void onSuccess(String url) {
        Log.i(TAG, "download url:" + url + " success");
        timer.suspend();
    }
    public void onError(String url, Downloader.ErrorCode e) {
        Log.i(TAG, "download url:" + url + " error:" + e);
        timer.suspend();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
