package com.example.saber.bitmapphotowall;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * Created by saber on 2017/6/17.
 */

public class SquareImageView extends ImageView{
    public SquareImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec,widthMeasureSpec);
    }
}
