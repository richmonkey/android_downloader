package com.newgame.downloader;

import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;

import static android.os.SystemClock.uptimeMillis;


class MD5 {
    private static final String TAG = "gamecenter";

    public static boolean checkMD5(String md5, File updateFile) {
        if (TextUtils.isEmpty(md5) || updateFile == null) {
            Log.e(TAG, "MD5 string empty or updateFile null");
            return false;
        }

        String calculatedDigest = calculateMD5(updateFile);
        if (calculatedDigest == null) {
            Log.e(TAG, "calculatedDigest null");
            return false;
        }

        Log.v(TAG, "Calculated digest: " + calculatedDigest);
        Log.v(TAG, "Provided digest: " + md5);

        return calculatedDigest.equalsIgnoreCase(md5);
    }

    public static String calculateMD5(File updateFile) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Exception while getting digest", e);
            return null;
        }

        InputStream is;
        try {
            is = new FileInputStream(updateFile);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Exception while getting FileInputStream", e);
            return null;
        }

        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            byte[] md5sum = digest.digest();
            BigInteger bigInt = new BigInteger(1, md5sum);
            String output = bigInt.toString(16);
            // Fill to 32 chars
            output = String.format("%32s", output).replace(' ', '0');
            return output;
        } catch (IOException e) {
            throw new RuntimeException("Unable to process file for MD5", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                Log.e(TAG, "Exception on closing MD5 input stream", e);
            }
        }
    }
}

public class Downloader {

    public static enum ErrorCode {
        ERROR_NONE,
        ERROR_DISK,
        ERROR_NETWORK,
        ERROR_CHECKSUM,
        ERROR_CANCELED,//内部使用
    };

    public static interface DownloaderObserver {
        public void onSuccess(String url);
        public void onError(String url, ErrorCode e);
    };

    private static class URLRequest {
        public String url;
        public String fileName;
        public String md5;
        public long totalSize;
        public long size;
        public int speed;//比特/秒
    };

    private final static String TAG = "gamecenter";
    private final static int CONCURRENT_COUNT = 1;
    private final static int RETRY_COUNT = 3;


    private static Downloader instance = new Downloader();

    public static Downloader getInstance() {
        return instance;
    }

    //p->r or p->r->z
    //等待队列
    private HashMap<String, URLRequest> pRequests = new HashMap<String, URLRequest>();
    //运行队列
    private HashMap<String, URLRequest> rRequests = new HashMap<String, URLRequest>();
    //僵尸队列
    private HashMap<String, URLRequest> zRequests = new HashMap<String, URLRequest>();

    private int threadCount = 0;

    //main thread handler;
    private Handler handler = new Handler();


    private ArrayList<DownloaderObserver> observers = new ArrayList<DownloaderObserver>();

    public Downloader() {

    }

    public void addObserver(DownloaderObserver ob) {
        if (observers.contains(ob)) {
            return;
        }
        observers.add(ob);
    }

    public void removeObserver(DownloaderObserver ob) {
        observers.remove(ob);
    }

    public synchronized void enqueue(String url, String fileName, int totalSize, String md5) {
        if (pRequests.containsKey(url) || rRequests.containsKey(url)) {
            Log.i(TAG, "contains key:" + url);
            return;
        }

        URLRequest req = new URLRequest();
        req.url = url;
        req.fileName = fileName;
        req.md5 = md5;
        req.totalSize = totalSize;
        File f = new File(fileName);
        req.size = f.length();

        if (totalSize > 0 && req.size >= totalSize) {
            Log.w(TAG, "size:" + req.size + " total size:" + totalSize);
            return;
        }
        pRequests.put(url, req);

        if (zRequests.containsKey(url)) {
            return;
        }

        if (threadCount >= CONCURRENT_COUNT) {
            return;
        }

        Runnable r = new Runnable(){

            public int now() {
                Date date = new Date();
                long t = date.getTime();
                return (int)(t/1000);
            }

            public void run(){
                Log.i(TAG, "download thread start");
                int idleTS = 0;

                while (true) {
                    boolean idle = Downloader.this.schedule();

                    //线程空闲5s后再退出
                    if (idle) {
                        int now = now();
                        if (now - idleTS > 5 && idleTS > 0) {
                            //exceed 5s idle
                            synchronized (Downloader.this) {
                                //last download thread
                                if (threadCount - 1 == 0 && !pRequests.isEmpty()) {
                                    continue;
                                } else {
                                    threadCount--;
                                    break;
                                }
                            }
                        } else if(idleTS == 0) {
                            idleTS = now;
                        }
                        try {
                            Thread.sleep(200);
                        } catch(InterruptedException e) {
                            e.printStackTrace();
                        }
                    } else {
                        idleTS = 0;
                    }
                }

                Log.i(TAG, "download thread exit");
            }
        };

        threadCount++;
        new Thread(r).start();

    }

    public synchronized void dequeue(String url) {
        if (pRequests.containsKey(url)) {
            pRequests.remove(url);
            Log.i(TAG, "remove url:" + url + " from pending queue");
            return;
        }

        if (rRequests.containsKey(url)) {
            //r->z
            zRequests.put(url, rRequests.remove(url));
            Log.i(TAG, "mv url:" + url + " from z to r");
            return;
        }

        Log.i(TAG, "can't dequeue url:" + url);
    }

    public synchronized boolean isInQueue(String url) {
        return (pRequests.containsKey(url) || rRequests.containsKey(url));
    }

    //获取下载速度
    public synchronized int getSpeed(String url) {
        if (rRequests.containsKey(url)) {
            URLRequest req = rRequests.get(url);
            return req.speed;
        }

        return 0;
    }

    //获取下载进度
    public synchronized int getProgress(String url) {
        if (pRequests.containsKey(url)) {
            URLRequest req = pRequests.get(url);
            if (req.totalSize == 0) {
                return 0;
            }
            return (int)(req.size*100/req.totalSize);
        }

        if (rRequests.containsKey(url)) {
            URLRequest req = rRequests.get(url);
            if (req.totalSize == 0) {
                return 0;
            }
            return (int)(req.size*100/req.totalSize);
        }

        return 0;
    }

    private  boolean schedule() {

        URLRequest req = null;
        synchronized(this) {
            if (this.pRequests.isEmpty()) {
                return true;
            }

            Iterator<URLRequest> iter = this.pRequests.values().iterator();
            while (iter.hasNext()) {
                URLRequest r = iter.next();
                if (!this.zRequests.containsKey(r.url)) {
                    req = r;
                    break;
                }
            }
            if (req == null) {
                return true;
            }

            //p->r
            this.rRequests.put(req.url, this.pRequests.remove(req.url));
            Log.i(TAG, "begin download url:" + req.url);
        }

        ErrorCode err = ErrorCode.ERROR_NONE;
        for (int i = 0; i < RETRY_COUNT; i++) {
            err = download(req);
            if (err == ErrorCode.ERROR_NETWORK) {
                //失败重试
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {

                }
                continue;
            } else {
                break;
            }
        }

        boolean inRunning = false;
        synchronized (this) {
            if (this.rRequests.containsKey(req.url)) {
                //下载失败
                this.rRequests.remove(req.url);
                inRunning = true;
            } else {
                this.zRequests.remove(req.url);
            }
        }
        if (inRunning) {
            final ErrorCode e = err;
            final String url = req.url;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Downloader.this.callOB(url, e);
                }
            });
        }

        return false;
    }

    private void callOB(String url, ErrorCode e) {
        for (DownloaderObserver ob : this.observers) {
            if (e == ErrorCode.ERROR_NONE) {
                ob.onSuccess(url);
            } else {
                ob.onError(url, e);
            }
        }
    }

    private static long getInstanceLength(HttpResponse response) {
        long length = -1;
        if (response != null) {
            Header[] range = response.getHeaders("Content-Range");
            if (range.length > 0) {
                // Get the header value
                String value = range[0].getValue();
                Log.i(TAG, "content range:" + value);
                // Split the value
                String[] section = value.split("/");

                try {
                    // Parse for the instance length
                    length = Long.parseLong(section[1]);
                } catch (NumberFormatException e) {
                    // The server returned an unknown "*" or invalid instance-length
                    Log.d(TAG, String.format("The HttpResponse contains an invalid instance-length: %s", value));
                }
            }
        }
        return length;
    }

    private static long getContentLength(HttpResponse response) {
        long length = -1;
        if (response != null) {
            Header[] range = response.getHeaders("Content-Length");
            if (range.length > 0) {
                // Get the header value
                String value = range[0].getValue();


                try {
                    // Parse for the instance length
                    length = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    // The server returned an unknown "*" or invalid instance-length
                    Log.d(TAG, String.format("The HttpResponse contains an invalid instance-length: %s", value));
                }
            }
        }
        return length;
    }
    private ErrorCode download(URLRequest req) {

        File f;
        long fileSize;
        f = new File(req.fileName);
        fileSize = f.length();

        if (req.size != fileSize) {
            Log.i(TAG, "req size:" + req.size + " not equal file size:" + fileSize);
            req.size = fileSize;
        }

        String uri = req.url;
        HttpParams httpParams = new BasicHttpParams();
        int timeoutConnection = 30000; //30s
        HttpConnectionParams.setConnectionTimeout(httpParams, timeoutConnection);
        int timeoutSocket = 60000; //60s
        HttpConnectionParams.setSoTimeout(httpParams, timeoutSocket);

        HttpClient httpClient = new DefaultHttpClient(httpParams);

        HttpResponse response;
        int statusCode;
        HttpHead headRequest = new HttpHead(uri);
        try {
            response = httpClient.execute(headRequest);
        } catch (Exception e) {
            return ErrorCode.ERROR_NETWORK;
        }
        statusCode = response.getStatusLine().getStatusCode();
        if (statusCode != HttpStatus.SC_OK) {
            return ErrorCode.ERROR_NETWORK;
        }

        long totalSize = getContentLength(response);
        if (totalSize != -1 && req.totalSize > 0 && req.totalSize != totalSize) {
            Log.w(TAG, "total size:" + req.totalSize + " not equal content length:" + totalSize);
        }
        if (totalSize != -1) {
            req.totalSize = totalSize;
        }

        Log.i(TAG, "total size:" + req.totalSize + " file size:" + fileSize);

        HttpGet request = new HttpGet(uri);
        if (req.totalSize > 0 && fileSize == req.totalSize) {
            Log.w(TAG, "Output file already exists. Skipping download.");
            return ErrorCode.ERROR_NONE;
        }
        if (req.totalSize > 0 && req.totalSize > fileSize) {
            request.addHeader("Range", String.format("bytes=%d-", fileSize));
        }

        try {
            response = httpClient.execute(request);
        } catch (Exception e) {
            return ErrorCode.ERROR_NETWORK;
        }

        statusCode = response.getStatusLine().getStatusCode();
        Log.i(TAG, "status code:" + statusCode);

        FileOutputStream fs;

        if (statusCode == HttpStatus.SC_OK) {
            //获取完整文件
            req.size = 0;
            try {
                //truncate file
                fs = new FileOutputStream(f, false);
            } catch (IOException e) {
                return ErrorCode.ERROR_DISK;
            }
        } else if (statusCode == HttpStatus.SC_PARTIAL_CONTENT) {
            //获取部分文件
            try {
                fs = new FileOutputStream(f, true);
            } catch (IOException e) {
                return ErrorCode.ERROR_DISK;
            }
        } else {
            return ErrorCode.ERROR_NETWORK;
        }

        HttpEntity entity = response.getEntity();
        if (entity == null) {
            return ErrorCode.ERROR_NETWORK;
        }

        int len = (int)entity.getContentLength();
        Log.i(TAG, "content length:" + len);

        InputStream inStream;
        try {
            inStream = response.getEntity().getContent();
        } catch (IOException e) {
            return ErrorCode.ERROR_NETWORK;
        }

        long begin = uptimeMillis();
        long nread = 0;
        byte[] buf = new byte[64*1024];
        while (nread < len) {
            synchronized (this) {
                if (zRequests.containsKey(req.url)) {
                    //已被取消
                    Log.i(TAG, "url:" + req.url + " be canceled");
                    return ErrorCode.ERROR_CANCELED;
                }
            }

            int n;
            try {
                n = inStream.read(buf, 0, 64 * 1024);
            } catch (IOException e) {
                return ErrorCode.ERROR_NETWORK;
            }
            if (n == -1) {
                break;
            }
            long end = uptimeMillis();

            nread += n;
            try {
                fs.write(buf, 0, n);
            } catch (IOException e) {
                return ErrorCode.ERROR_DISK;
            }
            req.size += n;
            if (end - begin > 0) {
                long speed = (nread*1000/(end - begin));
                if (speed < 0) {
                    Log.i(TAG, "speed:" + speed + "   " + (int)speed);
                }
                req.speed = (int)speed;
            }
        }

        try {
            inStream.close();
        } catch (IOException e) {
            return ErrorCode.ERROR_NETWORK;
        }

        if (nread != len) {
            return ErrorCode.ERROR_NETWORK;
        }
        try {
            fs.flush();
            fs.close();
        } catch (IOException e) {
            return ErrorCode.ERROR_DISK;
        }

        if (!TextUtils.isEmpty(req.md5) && !MD5.checkMD5(req.md5, f)) {
            return ErrorCode.ERROR_CHECKSUM;
        }
        return ErrorCode.ERROR_NONE;
    }
}
