# -*- coding: utf-8 -*-
# يتحقق أن ملفات «الما» لم تُعدَّل، بإدخال رمز الحماية.
import hashlib, os, re, sys
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass
HERE = os.path.dirname(os.path.abspath(__file__))

def body(path):
    raw = open(path, encoding="utf-8").read()
    return re.sub(r'\n?[#<][^\n]*ALMA-SEAL[^\n]*>?\s*$', '', raw), raw   # إزالة سطر الختم

def seal_in(raw):
    m = re.search(r'ALMA-SEAL[^\n]*?([0-9a-f]{64})', raw)
    return m.group(1) if m else None

def main_files():
    return [os.path.join(HERE, n) for n in ("alma.py", "index.html", "pdf.html")
            if os.path.isfile(os.path.join(HERE, n))]

files = main_files()
if not files:
    print("لم أجد ملف alma.py أو index.html أو pdf.html بجانب الأداة."); input(); sys.exit()
try:
    code = input("أدخل رمز الحماية: ").strip()
except EOFError:
    code = ""
print()
for p in files:
    b, raw = body(p)
    have = seal_in(raw)
    name = os.path.basename(p)
    if not have:
        print("•  %s: غير مختوم. شغّل «اختم-الحماية.py» أولاً." % name); continue
    calc = hashlib.sha256((code + "\n" + b).encode("utf-8")).hexdigest()
    if calc == have:
        print("✅  %s: النسخة موثّقة وغير معدّلة — تصميم م/ماهر العلفي." % name)
    else:
        print("⚠️  %s: النسخة معدّلة، أو الرمز غير صحيح." % name)
input("\nاضغط Enter للخروج.")
