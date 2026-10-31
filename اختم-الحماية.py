# -*- coding: utf-8 -*-
# يختم ملف «الما» برمز الحماية. من لا يعرف الرمز لا يستطيع إنتاج نسخة موثّقة.
import hashlib, os, re, sys
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass
HERE = os.path.dirname(os.path.abspath(__file__))

def main_file():
    for n in ("alma.py", "index.html"):
        p = os.path.join(HERE, n)
        if os.path.isfile(p): return p
    return None

p = main_file()
if not p:
    print("لم أجد ملف alma.py أو index.html بجانب الأداة."); input(); sys.exit()
raw = open(p, encoding="utf-8").read()
body = re.sub(r'\n?[#<][^\n]*ALMA-SEAL[^\n]*>?\s*$', '', raw).rstrip("\n")
try:
    code = input("اختر رمز الحماية (احفظه ولا تخبر به أحداً): ").strip()
except EOFError:
    code = ""
if not code:
    print("لا يمكن الختم برمز فارغ."); input(); sys.exit()
seal = hashlib.sha256((code + "\n" + body).encode("utf-8")).hexdigest()
line = ("<!-- ALMA-SEAL v9 | %s -->" if p.endswith(".html") else "# ALMA-SEAL v9 | %s") % seal
open(p, "w", encoding="utf-8", newline="\n").write(body + "\n" + line + "\n")
print("\n✅  تم ختم:", os.path.basename(p))
print("احتفظ بالرمز. للتحقق لاحقاً شغّل «تحقق-الحماية.py».")
input("\nاضغط Enter للخروج.")
