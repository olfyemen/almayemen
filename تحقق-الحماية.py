# -*- coding: utf-8 -*-
# يتحقق أن ملف «الما» لم يُعدَّل، بإدخال رمز الحماية.
import glob, hashlib, os, re, sys
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass
HERE = os.path.dirname(os.path.abspath(__file__))

def body(path):
    t = open(path, encoding="utf-8").read()
    t = re.sub(r'\n?[#<][^\n]*ALMA-SEAL[^\n]*>?\s*$', '', t)   # إزالة سطر الختم
    return t, open(path, encoding="utf-8").read()

def seal_in(raw):
    m = re.search(r'ALMA-SEAL[^\n]*?([0-9a-f]{64})', raw)
    return m.group(1) if m else None

def main_file():
    for n in ("alma.py", "index.html"):
        p = os.path.join(HERE, n)
        if os.path.isfile(p): return p
    return None

p = main_file()
if not p:
    print("لم أجد ملف alma.py أو index.html بجانب الأداة."); input(); sys.exit()
b, raw = body(p)
have = seal_in(raw)
if not have:
    print("هذا الملف غير مختوم. شغّل «اختم-الحماية.py» أولاً."); input(); sys.exit()
try:
    code = input("أدخل رمز الحماية: ").strip()
except EOFError:
    code = ""
calc = hashlib.sha256((code + "\n" + b).encode("utf-8")).hexdigest()
print()
if calc == have:
    print("✅  النسخة موثّقة وغير معدّلة — تصميم م/ماهر العلفي.")
else:
    print("⚠️  النسخة معدّلة، أو الرمز غير صحيح.")
input("\nاضغط Enter للخروج.")
