#!/bin/bash
# دفعة تفريغ واحدة (تشغَّل بمهمة مجدولة كل يوم بعد تجدد حصة Gemini ~٠٧:٠٠ UTC)؛ السجل في transcribe.log
# يلزم: export NAS_PASSWORD='…' GEMINI_KEY='…'
cd "$(dirname "$0")"
timeout -k 20 3600 python3 transcribe_series.py --user manus --password "$NAS_PASSWORD" --gemini-key "$GEMINI_KEY" --all --workers 2 --workdir ./transcribe-work >> transcribe.log 2>&1
echo "rc=$? $(date +%H:%M:%S)" >> transcribe.log
