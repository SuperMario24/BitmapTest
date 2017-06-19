package com.example.saber.bitmapphotowall;

import android.content.Context;
import android.content.DialogInterface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.List;

/**
 * Created by saber on 2017/6/17.
 */

public class ImageAdapter extends BaseAdapter{

    private List<String> mUrlList;
    private Context context;
    private LayoutInflater inflater;
    private boolean mIsGridViewIdle = true;//是否是静止状态
    private boolean mCanGetBitmapFromNetWork;//判断网络
    private ImageLoader mImageLoader;
    private int mImageWidth;
    private int mNetWorkType;


    public ImageAdapter(List<String> mUrlList, Context context) {
        this.mUrlList = mUrlList;
        this.context = context;
        inflater = LayoutInflater.from(context);
        mImageLoader = ImageLoader.build(context);
        mNetWorkType = getNetWorkState();

        if(mNetWorkType != 2){
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("注意");
            builder.setMessage("初次试用会从网络下载大概5MB的图片，确认要下载吗？");
            builder.setPositiveButton("是", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    mCanGetBitmapFromNetWork = true;
                    notifyDataSetChanged();
                }
            });
            builder.setNegativeButton("否",null);
            builder.show();
        }else {
            mCanGetBitmapFromNetWork = true;
        }


    }

    //判断网络状态
    private int getNetWorkState() {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        if(networkInfo != null && networkInfo.isAvailable()){
            return networkInfo.getType();
        }else {
            Toast.makeText(context, "当前网络不可用！", Toast.LENGTH_SHORT).show();
        }

        return -1;
    }

    @Override
    public int getCount() {
        return mUrlList.size();
    }

    @Override
    public String getItem(int position) {
        return mUrlList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {

        ViewHolder holder = null;
        if(convertView == null){
            convertView = inflater.inflate(R.layout.image_list_item,parent,false);
            holder = new ViewHolder();
            holder.imageView = (ImageView) convertView.findViewById(R.id.image);
            convertView.setTag(holder);
        }else {
            holder = (ViewHolder) convertView.getTag();
        }

        ImageView imageView = holder.imageView;
        final String tag = (String) imageView.getTag();
        final String url = getItem(position);
        if(!url.equals(tag)){
            imageView.setImageDrawable(context.getResources().getDrawable(R.mipmap.ic_launcher));
        }

        if(mIsGridViewIdle && mCanGetBitmapFromNetWork){
            imageView.setTag(url);
            mImageLoader.bindBitmap(url,imageView,mImageWidth,mImageWidth);
        }
        return convertView;
    }


    class ViewHolder{
        ImageView imageView;
    }


}
