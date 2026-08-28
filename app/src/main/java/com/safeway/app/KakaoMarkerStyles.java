package com.safeway.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.annotation.DrawableRes;
import androidx.appcompat.content.res.AppCompatResources;

import com.kakao.vectormap.KakaoMap;
import com.kakao.vectormap.label.LabelStyle;
import com.kakao.vectormap.label.LabelStyles;

final class KakaoMarkerStyles {
    private KakaoMarkerStyles() {
    }

    static LabelStyles addMarkerStyles(
            Context context,
            KakaoMap kakaoMap,
            @DrawableRes int drawableRes,
            int textSize,
            int textColor
    ) {
        LabelStyle style;
        Bitmap icon = vectorToBitmap(context, drawableRes);
        if (icon == null) {
            style = LabelStyle.from().setTextStyles(textSize, textColor);
        } else {
            style = LabelStyle.from(icon).setTextStyles(textSize, textColor);
        }
        return kakaoMap.getLabelManager().addLabelStyles(LabelStyles.from(style));
    }

    private static Bitmap vectorToBitmap(Context context, @DrawableRes int drawableRes) {
        Drawable drawable = AppCompatResources.getDrawable(context, drawableRes);
        if (drawable == null) {
            return null;
        }

        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            int fallbackSize = (int) (48 * context.getResources().getDisplayMetrics().density);
            width = fallbackSize;
            height = fallbackSize;
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }
}
