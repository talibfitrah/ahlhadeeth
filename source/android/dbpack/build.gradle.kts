// حزمة أصول Play (install-time): قاعدة البيانات المضغوطة تصل مع تثبيت نسخة المتجر من خوادم Google،
// فلا ينتظر أول فتح تنزيلًا من NAS (رُفض الإصدار ١٩ لأن شاشة التنزيل الأول بدت «متجمّدة» للمراجِع).
// لا تدخل إلا في ملفات AAB؛ ملفات APK للتوزيع المباشر لا تحويها وتبقى تنزّل من الشبكة.
// الملف الكبير db/ahl_alhadeeth.db.gz لا يُحفظ في git — طريقة جلبه في CLAUDE.md (جدول «Build and verify»).
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("dbpack")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
