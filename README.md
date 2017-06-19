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




































