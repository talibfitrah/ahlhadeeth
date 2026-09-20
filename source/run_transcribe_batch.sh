#!/bin/bash
# دفعة تفريغ واحدة (تشغَّل بمهمة مجدولة كل يوم بعد تجدد حصة Gemini ~٠٧:٠٠ UTC)؛ السجل في transcribe.log
# يلزم: export NAS_PASSWORD='…' GEMINI_KEY='…' (يقرؤهما السكربت من البيئة؛ لا يُمرَّران في سطر الأوامر حتى لا يظهرا في ps)
cd "$(dirname "$0")"
timeout -k 20 3600 python3 transcribe_series.py --user manus --all --workers 2 --workdir ./transcribe-work >> transcribe.log 2>&1
echo "rc=$? $(date +%H:%M:%S)" >> transcribe.log
