package com.example.saber.bitmapphotowall;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by saber on 2017/6/17.
 */

public class ImageLoader {

    private static final String TAG = "ImageLoader";

    private static final int MESSAGE_POST_RESULT = 1;

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();//获取CPU核心数
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;//线程池核心线程数
    private static final int MAXIMUN_POOL_SIZE = CPU_COUNT * 2 + 1;//线程池最大线程数
    private static final long KEEP_ALIVE = 10L;//超时时长

    private static final int TAG_KEY_URI = R.id.imageloader_uri;
    private static final long DISK_CACHE_SIZE = 1024 * 1024 *50;
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int DISK_CACHE_INDEX = 0;
    private boolean mIsDiskLruCacheCreated = false;

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {

        private final AtomicInteger mCount = new AtomicInteger(1);//线程安全的i++,++i

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r,"ImageLoader#"+mCount.getAndDecrement());
        }
    };

    //创建线程池
    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE,MAXIMUN_POOL_SIZE,
            KEEP_ALIVE, TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>(),sThreadFactory);

    private Handler mMainHandler = new Handler(Looper.getMainLooper()){
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
            String uri = (String) imageView.getTag(TAG_KEY_URI);
            if(uri.equals(result.uri)){
                imageView.setImageBitmap(result.bitmap);
            }else {
                Log.w(TAG, "set image bitmap,but url has changed,ingored!" );
            }

        }
    };

    private Context mContext;
    private ImageResizer mImageResizer = new ImageResizer();
    private LruCache<String,Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;

    private ImageLoader(Context context){
        mContext = context.getApplicationContext();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);//获取可用内存
        int cacheSize = maxMemory / 8;//内存缓存大小

        //创建内存缓存
        mMemoryCache = new LruCache<String,Bitmap>(cacheSize){
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };

        //创建磁盘缓存文件
        File diskCacheDir = getDiskCacheDir(mContext,"bitmap");
        if(!diskCacheDir.exists()){
            diskCacheDir.mkdirs();
        }
        //判断剩余可用空间是否大于磁盘缓存的大小
        if(getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE){
            try {
                //创建磁盘缓存
                mDiskLruCache = DiskLruCache.open(diskCacheDir,1,1,DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static ImageLoader build(Context context){
        return new ImageLoader(context);
    }

    /**
     * 从内存缓存中获取Bitmap
     * @param key
     * @return
     */
    private Bitmap getBitmapFromMemCache(String key){
        return mMemoryCache.get(key);
    }

    /**
     * 往内存缓存中添加Bitmap
     * @param key
     * @param bitmap
     */
    private void addBitmapToMemoryCache(String key,Bitmap bitmap){
        if(getBitmapFromMemCache(key) != null){
            mMemoryCache.put(key,bitmap);
        }
    }


    /**
     * 异步加载图片
     * @param uri
     * @param imageView
     */
    public void bindBitmap(final String uri,final ImageView imageView){
        bindBitmap(uri,imageView,0,0);
    }

    public void bindBitmap(final String uri, final ImageView imageView, final int reqWidth, final int reqHeight){
        imageView.setTag(TAG_KEY_URI,uri);
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if(bitmap != null){
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(uri,reqWidth,reqHeight);
                if(bitmap != null){
                    LoaderResult result = new LoaderResult(imageView,uri,bitmap);
                    //获取到Bitmap后发送消息，传递Bitmap
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT,result).sendToTarget();
                }
            }


        };

        //开启线程池
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }

    /**
     * 同步加载Bitmap
     * @param uri
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    private Bitmap loadBitmap(String uri, int reqWidth, int reqHeight) {
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if(bitmap != null){
            Log.d(TAG,"loadBitmapFromMemCache,url:"+uri);
            return bitmap;
        }

        try {
            bitmap = loadBitmapFromDiskCache(uri,reqWidth,reqHeight);
            if(bitmap != null){
                Log.d(TAG,"loadBitmapFromDisk,url:"+uri);
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(uri,reqWidth,reqHeight);
            Log.d(TAG,"loadBitmapFromHttp,url:"+uri);

        } catch (IOException e) {
            e.printStackTrace();
        }


        if(bitmap == null && !mIsDiskLruCacheCreated){
            Log.w(TAG,"encounter error,DiskLruCache is not created.");
            bitmap = downloadBitmapFromUrl(uri);
        }


        return bitmap;

    }

    /**
     * 从内存缓存中获取Bitmap
     * @param url
     * @return
     */
    private Bitmap loadBitmapFromMemCache(String url) {
        final String key = hashKeyFromUrl(url);
        //通过key在内存缓存中获取Bitmap
        Bitmap bitmap = getBitmapFromMemCache(key);
        return bitmap;
    }

    /**
     * 从磁盘缓存中读取Bitmap
     * @param uri
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    private Bitmap loadBitmapFromDiskCache(String uri, int reqWidth, int reqHeight) throws IOException {
        if(Looper.myLooper() == Looper.getMainLooper()){
            Log.w(TAG, "load bitmap from UI Thread,it is not recommended." );
        }
        if(mDiskLruCache == null){
            return null;
        }

        Bitmap bitmap = null;
        String key = hashKeyFromUrl(uri);
        //磁盘缓存的读取要通过Snapshot类
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if(snapshot != null){
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            //获取文件描述符，压缩图片时要用到
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = mImageResizer.decodeSampleFromFileDescriptor(fileDescriptor,reqWidth,reqHeight);

            //磁盘读取后添加到内存缓存
            if(bitmap != null){
                addBitmapToMemoryCache(key,bitmap);
            }
        }
        return bitmap;

    }

    /**
     * 从网络下载图片
     * @param uri
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    private Bitmap loadBitmapFromHttp(String uri, int reqWidth, int reqHeight) throws IOException {

        if(Looper.myLooper() == Looper.getMainLooper()){
            throw new RuntimeException("can not visit network from UI Thread.");
        }

        if(mDiskLruCache == null){
            return null;
        }

        String key = hashKeyFromUrl(uri);
        //创建 DiskLruCache的Editor对象
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if(editor != null){
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            //如果下载成功
            if(downloadUrlToStream(uri,outputStream)){
                editor.commit();
            }else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }

        return loadBitmapFromDiskCache(uri,reqWidth,reqHeight);
    }

    /**
     * 下载图片为流
     * @param uri
     * @param outputStream
     * @return
     */
    private boolean downloadUrlToStream(String uri, OutputStream outputStream) {

        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;

        try {
            final URL url = new URL(uri);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(),IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream,IO_BUFFER_SIZE);

            int b;
            while((b = in.read()) != -1){
                out.write(b);
            }

            return true;

        } catch (IOException e) {
            Log.e(TAG, "downloadBitmap failed."+e );
            e.printStackTrace();
        }finally {
            if(urlConnection != null){
                urlConnection.disconnect();
            }
            try {
                if(out != null){
                    out.close();
                }
                if(in != null){
                    in.close();
                }

            } catch (IOException e) {
                e.printStackTrace();
            }

        }

        return false;

    }


    /**
     * 从网络获取图片
     * @param uri
     * @return
     */
    private Bitmap downloadBitmapFromUrl(String uri) {

        Bitmap bitmap = null;
        HttpURLConnection urlConnection = null;
        BufferedInputStream in = null;

        try {
            final URL url = new URL(uri);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(),IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);

        } catch (IOException e) {
            Log.e(TAG, "Error on downloadBitmap:"+e );
        }finally {
            if(urlConnection != null){
                urlConnection.disconnect();
            }
            try {
                if(in != null){
                    in.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return bitmap;

    }

    /**
     * 将url转化成key保存
     * @param url
     * @return
     */
    private String hashKeyFromUrl(String url) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(url.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }

    private String bytesToHexString(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<digest.length;i++){
            String hex = Integer.toHexString(0xFF & digest[i]);
            if(hex.length() == 1){
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }


    /**
     * 获取剩余空间大小
     * @param diskCacheDir
     * @return
     */
    private long getUsableSpace(File diskCacheDir) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD){
            return diskCacheDir.getUsableSpace();
        }
        //StatFs用于获取存储空间
        final StatFs stats = new StatFs(diskCacheDir.getPath());
        //getAvailableBlocks() 文件系统中可被应用程序使用的空闲存储区块的数量
        //getBlockSize()文件系统中每个存储区块的字节数
        return stats.getBlockSize() * stats.getAvailableBlocks();
    }

    /**
     * 获取磁盘缓存文件
     * @param mContext
     * @param bitmap
     * @return
     */
    private File getDiskCacheDir(Context mContext, String bitmap) {
        //sd卡是否可用
        boolean externalStorageAvailable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);

        final String cachePath;
        if(externalStorageAvailable){
            cachePath = mContext.getExternalCacheDir().getPath();
        }else {
            cachePath = mContext.getCacheDir().getPath();
        }
        //File.separator才是用来分隔同一个路径字符串中的目录的，例如：C:\Program Files\Common Files,就是指“\”
        return new File(cachePath + File.pathSeparator + bitmap);

    }


    class LoaderResult {
        public ImageView imageView;
        public String uri;
        public Bitmap bitmap;

        public LoaderResult(ImageView imageView,String uri,Bitmap bitmap){
            this.imageView = imageView;
            this.uri = uri;
            this.bitmap = bitmap;
        }
    }

}
