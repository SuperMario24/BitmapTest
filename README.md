# BitmapTest


一.概述

1.一个应用的内存是有限的，大约16M,这导致加载Bitmap的时候很容易出现内存溢出，这时就需要对Bitmap进行压缩，并应用缓存策略，目前比较常用的缓存策略为
内存缓存LruCache和磁盘缓存DiskLruCache，这种缓存策略的核心思想是：当缓存快满时，会淘汰近期最少使用的缓存目标。


二.Bitmap的高效加载

1.BitmapFactory提供了四个方法：decodeFile，decodeResource，decodeStream，decodeByteArray，分别用于支持从文件系统、资源、输入流和字节数组中
加载出一个Bitmap对象。其中decodeFile,decodeResource又间接调用了decodeStream方法，这四个方法最终是在Android底层实现的。


2.高效加载Bitmap：

（1）通过BitmapFactory.Options就可以按照一定的采样率来加载缩小后的图片，将缩小后的图片在ImageView显示，在一定程度上避免OOM。

（2）通过BitmapFactory.Options来缩放图片，主要使用到了它的inSimpleSize参数，即采样率。当inSimpleSize为2时，其宽高均为原图大小的1/2，其像素数
为原图的1/4。inSimpleSize的取值应该总是2的倍数，如果外界传统给系统的不是2的倍数，系统会向下取整并选择一个最接近的2的指数来代替，比如3，系统就会选择
2来代替。


（3）获取采样率的步骤：

1.将BitmapFactory.Options的inJustDecodeBounds参数设为true，加载图片。
2.从BitmapFactory.Options中取出图片的原始宽高信息，他们对应BitmapFactory.Options的outWidth和outHeight参数。
3.根据采样率的规则结合目标View的大小计算出采样率inSimpleSize。
4.将BitmapFactory.Options的inJustDecodeBounds参数设为false，重新加载图片。

注意：BitmapFactory.Options的inJustDecodeBounds参数设为true时，BitmapFactory只会解析图片的原始宽高，并不会真正去加载图片。

下面是这4个流程的实现：

    public Bitmap decodeSampleBitmapFromResource(Resources res,int resId,int reqWidth,int reqHeight){

        final BitmapFactory.Options options = new BitmapFactory.Options();
        //设为true后只会获取图片原始宽高信息，并不会真正加载图片----------1
        options.inJustDecodeBounds = true;
        //加载图片-------------------2
        decodeResource(res,resId,options);

        //计算inSampleSize--------------------3
        options.inSampleSize = calculateInSimpleSize(options,reqWidth,reqHeight);

        //设为false,重新加载图片---------------------------4
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(res,resId,options);

    }



    private int calculateInSimpleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {

        if(reqHeight == 0 || reqWidth == 0){
            return 1;
        }

        //获取原始宽高
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSimpleSize = 1;

        if(height > reqHeight || width > reqWidth){
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while((halfHeight / inSimpleSize) >= reqHeight && (halfWidth / inSimpleSize) >= reqWidth){
                inSimpleSize *= 2;
            }
        }

        Log.d(TAG,"inSimpleSize:"+inSimpleSize);
        return inSimpleSize;

    }

除了BitmapFactory的decodeResource方法，其他三个decode系列方法也是支持采样加载的。



三.Android中的缓存策略

1.LruCache

（1）Android3.1所提供的一个缓存类，通过v4包兼容到早起版本，为了兼容Android2.2版本，在使用LruCache时建议采用v4包中提供了LruCache。

（2）LruCache是一个泛型类，它内部采用LinkedHashMap以强引用的方式储存外界的缓存对象，其提供了get和put方法来完成缓存的获取和添加操作：
1.强引用：直接的对象引用
2.软引用:当一个对象只有软引用存在时，系统内存不足时此对象会被gc回收
3.弱引用：当一个对象只有弱引用存在时，此对象随时被gc回收

另外LruCache是线程安全的，下面是LruCache的定义：

    public class LruCache<K, V> {
        private final LinkedHashMap<K, V> map;
        ....
    }

（1）LruCache的实现比较简单，下面展示了LruCache的典型初始化过程：

          int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);//获取可用内存
          int cacheSize = maxMemory / 8;//内存缓存大小

          mMemoryCache = new LruCache<String,Bitmap>(cacheSize){
              @Override
              protected int sizeOf(String key, Bitmap value) {
                  return value.getRowBytes() * value.getHeight() / 1024;
              }
          };
只需要提供缓存的总容量大小和重写sizeOf方法即可。sizeOf方法的作用是计算缓存对象的大小。除了LruCache的创建以外，还有缓存的获取和添加：

（2）从LruCache中获取一个缓存对象

       private Bitmap getBitmapFromMemCache(String key){
          return mMemoryCache.get(key);
      }

（3）往LruCache中添加一个缓存对象

       private void addBitmapToMemoryCache(String key,Bitmap bitmap){
            if(getBitmapFromMemCache(key) != null){
                mMemoryCache.put(key,bitmap);
            }
       }
从Android3.1开始，LruCache已经是Android源码的一部分了。




2.DiskLruCache

（1）DiskLruCache的创建：

DiskLruCache不能通过构造方法来创建，它提供了open方法用于创建自身：

   public static DiskLruCache open(File directory, int appVersion, int valueCount, long maxSize){
   ...
   }
open方法有四个参数，第一个参数表示磁盘缓存在文件系统中的存储路径，缓存路径可以选择SD卡上的路径，具体是指/sdcard/Android/data/package_name/cache
目录。如果希望用户卸载后就删除缓存文件，那么就选择删除SD卡上的缓存目录。

第二个参数表示应用版本号，一般设为1即可。当版本号发生改变时DiskLruCache会清空之前所有的缓存文件，实际意义不大，所以1比较好。

第三个参数表示单个字节所对应的数据的个数，一般设为1即可。

第四个表示缓存的总大小，比如50MB,当缓存大小超出这个设定值后，DiskLruCache会清除一些缓存数据从而保证总大小不超过这个设定值。

下面是一个典型的DiskLruCache的创建过程：

          private static final long DISK_CACHE_SIZE = 1024 * 1024 *50;
          File diskCacheDir = getDiskCacheDir(mContext,"bitmap");
          if(!diskCacheDir.exists()){
              diskCacheDir.mkdirs();
          }
          mDiskLruCache = DiskLruCache.open(diskCacheDir,1,1,DISK_CACHE_SIZE);
                

（2）DiskLruCache缓存的添加：

DiskLruCache的缓存添加操作是通过Editor完成的，Editor表示一个缓存的编辑对象。这里以图片缓存为例，首先需要获取图片url所对应的key，再根据key
就可以通过edit（）来获取Editor对象。如果这个缓存正在被编辑，那么edit（）就会返回null，之所以要把url转换为key，是因为图片url中很可能有特殊的字符
，这将影响url在Android中的使用。一般采用url的MD5的值作为key。如下所示：

         private String hashKeyFromUrl(String url) {
              String cacheKey;
              try {
                  final MessageDigest mDigest = MessageDigest.getInstance("MD5");//获取MD5对象
                  mDigest.update(url.getBytes());
                  cacheKey = bytesToHexString(mDigest.digest());//把字节装换为String
              } catch (NoSuchAlgorithmException e) {
                  e.printStackTrace();
                  cacheKey = String.valueOf(url.hashCode());//否则使用url的hashcode值
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

将图片的url转换为key以后就可以获取Editor对象了，对于这个key来说，如果当前不存在其他Editor对象，那么edit（）就会返回一个新的Editor对象，通过
它就可以得到一个文件输出流。需要注意的的是，由于前面open方法创建DiskLruCache时一个节点只能有一个数据，因此下面的DISK_CACHE_INDEX常量直接设
为0即可。如下所示：

            String key = hashKeyFromUrl(uri);//通过md5把url转换为key
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

有了文件输出流后，当从网络上下载图片时，图片就可以通过这个输出流写入到文件系统上了（磁盘缓存）：

        
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

经过上面的步骤，并没有真正将图片写入文件系统，还必须通过Editor的commit来提交写入操作，如果图片下载过程发生了异常，还可以通过Editor的abort来放弃
整个操作:

                   if(downloadUrlToStream(uri,outputStream)){
                        editor.commit();
                    }else {
                        editor.abort();
                    }
                    mDiskLruCache.flush();

经过上面几个步骤，图片已被正确的写入到文件磁盘缓存了，接下来图片的获取操作就不需要通过网络请求了。



（3）缓存的查找：

缓存的查找过程也要将url转换成key，然后通过DiskLruCache的get方法得到一个Snapshot对象，通过Snapshot对象就可以得到缓存的文件输入流，有了输入流，
自然可以得到Bitmap对象了。通过BitmapFactory.Options加载一张缩放后的图片。但是那种方法对FileInputStream的缩放存在问题，因为FileInputStream
是一种有序的文件流，但两次的decodeStram调用影响了文件流的位置属性，导致第二次decideStream得到的是null,为了解决这个问题，我们可以通过文件流得到
所对应的文件描述符，然后再通过BitmapFactory.decodeFileDescriptor方法来加载一张缩放后的图片，这个过程实现如下：

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

    public Bitmap decodeSampleFromFileDescriptor(FileDescriptor fd, int reqWidth, int reqHeight){

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        //用文件描述符去解析Bitmap
        BitmapFactory.decodeFileDescriptor(fd,null,options);

        options.inSampleSize = calculateInSimpleSize(options,reqWidth,reqHeight);

        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFileDescriptor(fd,null,options);

    }
两种压缩图片的方法其中计算inSimpleSize采样率的方法是一致的。



四.ImageLoader的实现


1.图片压缩功能的实现:图片压缩的类：ImageResizer

    public class ImageResizer {

        private static final String TAG = "ImageResizer";

        public ImageResizer(){
            super();
        }

        /**
         * 内存缓存的图片缩放功能
         * @param res
         * @param resId
         * @param reqWidth
         * @param reqHeight
         * @return
         */
        public Bitmap decodeSampleBitmapFromResource(Resources res,int resId,int reqWidth,int reqHeight){

            final BitmapFactory.Options options = new BitmapFactory.Options();
            //设为true后只会获取图片原始宽高信息，并不会真正加载图片
            options.inJustDecodeBounds = true;
            decodeResource(res,resId,options);

            //计算inSampleSize
            options.inSampleSize = calculateInSimpleSize(options,reqWidth,reqHeight);

            //设为false,重新加载图片
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeResource(res,resId,options);

        }

        /**
         * 磁盘缓存解析bitmap，不能用FileIntputStream是因为两次decodeStream调用影响了文件流的位置属性，第二次decodeStream返回null
         * @param fd
         * @param reqWidth
         * @param reqHeight
         * @return
         */
        public Bitmap decodeSampleFromFileDescriptor(FileDescriptor fd, int reqWidth, int reqHeight){

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            //用文件描述符去解析Bitmap
            BitmapFactory.decodeFileDescriptor(fd,null,options);

            options.inSampleSize = calculateInSimpleSize(options,reqWidth,reqHeight);

            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFileDescriptor(fd,null,options);

        }

        /**
         * 计算采样率inSimpleSize
         * @param options
         * @param reqWidth
         * @param reqHeight
         * @return
         */
        private int calculateInSimpleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {

            if(reqHeight == 0 || reqWidth == 0){
                return 1;
            }

            //获取原始宽高
            final int height = options.outHeight;
            final int width = options.outWidth;
            int inSimpleSize = 1;

            if(height > reqHeight || width > reqWidth){
                final int halfHeight = height / 2;
                final int halfWidth = width / 2;

                while((halfHeight / inSimpleSize) >= reqHeight && (halfWidth / inSimpleSize) >= reqWidth){
                    inSimpleSize *= 2;
                }
            }

            Log.d(TAG,"inSimpleSize:"+inSimpleSize);
            return inSimpleSize;

        }


    }

2.内存缓存和磁盘缓存的实现：

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
（1）内存缓存的添加和读取：

        private Bitmap getBitmapFromMemCache(String key){
            return mMemoryCache.get(key);
        }

        private void addBitmapToMemoryCache(String key,Bitmap bitmap){
            if(getBitmapFromMemCache(key) != null){
                mMemoryCache.put(key,bitmap);
            }
        }

（2）磁盘缓存的添加和读取稍微复杂一些。磁盘缓存的添加需要通过Editor来完成，磁盘的读取需要通过Snapshot来完成，通过Snapshot可以得到对象对应的
FileInputStream，通过FileInputStream可以得到FileDescriptor文件描述符，可以用它得到Bitmap：

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
    

（3）同步加载和异步加载的接口设计

先看同步加载：

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

这个方法不能在主线程中调用，否则就抛出异常，这个执行环境的检查是在loadBitmapFromHttp中实现的，再看异步加载：

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

bindBitmap方法会尝试从内存缓存中读取图片，如果读取成功就直接返回结果，否则就会在线程池中去调用loadBitmap方法，当图片加载成功后再将图片、图片的地址
以及需要绑定的ImageView封装成一个LoadResult对象，然后再通过Handler向主线程发一个消息，这样就可以在主线程中设置图片了。

用线程池，不用普通线程的原因：随着列表的滑动这可能会产生大量的线程，这并不利于整体效率的提升。

分析完线程池，下面看一下handler的实现，为了解决由于View复用所导致列表错位这一问题，再给ImageView设置图片之前都会检查它的url有没有发生改变，如果
发生改变就不再给他设置图片。这样就解决了列表的错位问题。

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

到此为止，ImageLoader的细节都已经做了全面的分析，ImageLoader的完整代码见项目中的ImageLoader.java。




五.照片墙效果的实现

1.优化列表卡顿的现象：

（1）不要在getView中执行耗时操作，这种操作必须通过异步来处理，就像ImageLoader那样。

（2）考虑在列表滑动的时候停止加载图片，等列表停下来以后再加载图片仍然可以获得良好的用户体验：

    public void onScrollStateChanged(AbsListView view,int scrollState){
        if(scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE){
            mIsGridViewIdle = true;
            notifyDataSetChanged();
        }else {
            mIsGridViewIdle = false;
        }
    }

（3）在某些特殊的情况下，列表还是会有偶尔卡顿的现象，这个时候还可以开启硬件加速，绝大多数情况下硬件加速都可以解决莫名的卡顿问题，
通过设置android：hardwareAccelerated="true"即可为Activity开启硬件加速。





















