#!/usr/bin/env bash
# الما — مدير الملفات · إعداد م/ماهر العلفي / 00967777408089
cd "$(dirname "$0")"
if [ ! -f .deps_ok ]; then
  echo "تثبيت المكتبات لأول مرة..."
  python3 -m pip install -r requirements.txt && touch .deps_ok
fi
python3 app.py "$@"
