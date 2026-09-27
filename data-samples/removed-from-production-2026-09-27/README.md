# محتوى تجريبي أُزيل من الإنتاج — ٢٧ سبتمبر ٢٠٢٦

أُزيل بطلب المالك قبل مراجعة Google Play حتى لا يظهر محتوى تجريبي لمستخدمي النسخة الجديدة:

| ما أُزيل | من أين | نسخته هنا |
|---|---|---|
| الشيخ «مثال: شيخ مضاف (تجريبي)» (`key: example-sheekh`، ثلاث سلاسل فارغة) | `shared-content.json` (المشاركة `4aPZIQpaW`) | `example-sheekh.shared-entry.json` |
| الحزمة «حزمة تجريبية — مثال على صيغة الحزم» (`id: example-pack`) | قائمة `packs` في `manifest.json` (المشاركة `gIX4l2mhu`) | `manifest.packs-entry.json` — وملف الحزمة نفسه هو `../pack-example.json` (مطابق بايتًا لبايت لما على الخادم، وباقٍ على NAS في المشاركة `fY5iCrH5n` دون أن يُشار إليه) |

`shared-content.before-2026-09-27.json` و`manifest.before-2026-09-27.json` نسختا الملفين الحيّين كما كانا قبل التعديل مباشرة.

**الاسترجاع:** أعد إدراج الإدخال في `sheekhs` (أو في `packs`) ثم ارفع الملف إلى `/downloads/ahl-alhadeeth` بأداة `source/nas.py` — كتابة إلى الإنتاج تحتاج إذن المالك.

**ملاحظة:** شواهد الحذف (`removed`) في التطبيق على مستوى الدرس فقط؛ الأجهزة التي جلبت الشيخ التجريبي سابقًا يبقى عندها اسمه بلا دروس (لا يوجد تقليم للمشايخ الفارغين)، والتثبيتات الجديدة لا تراه.

Removed at the owner's request on 2026-09-27: the test sheikh `example-sheekh` from the live shared-content feed and the `example-pack` entry from the live manifest. Full pre-change copies and the extracted entries are in this folder; restore by re-inserting and uploading with `source/nas.py` (production write — owner go-ahead required).
