package com.example.saber.bitmapphotowall;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.FileDescriptor;

import static android.graphics.BitmapFactory.decodeResource;

/**
 * Created by saber on 2017/6/17.
 */

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
