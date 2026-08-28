package com.safeway.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

final class PhoneUtils {
    private PhoneUtils() {
    }

    static void dial(Context context, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            Toast.makeText(context, "등록된 전화번호가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_DIAL);
        intent.setData(Uri.parse("tel:" + phoneNumber.trim()));
        context.startActivity(intent);
    }
}
