# -*- coding: utf-8 -*-
# يختم ملفات «الما» برمز الحماية. من لا يعرف الرمز لا يستطيع إنتاج نسخة موثّقة.
import hashlib, os, re, sys
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass
HERE = os.path.dirname(os.path.abspath(__file__))

def main_files():
    return [os.path.join(HERE, n) for n in ("alma.py", "index.html", "pdf.html")
            if os.path.isfile(os.path.join(HERE, n))]

files = main_files()
if not files:
    print("لم أجد ملف alma.py أو index.html أو pdf.html بجانب الأداة."); input(); sys.exit()
try:
    code = input("اختر رمز الحماية (احفظه ولا تخبر به أحداً): ").strip()
except EOFError:
    code = ""
if not code:
    print("لا يمكن الختم برمز فارغ."); input(); sys.exit()
print()
for p in files:
    raw = open(p, encoding="utf-8").read()
    body = re.sub(r'\n?[#<][^\n]*ALMA-SEAL[^\n]*>?\s*$', '', raw).rstrip("\n")
    seal = hashlib.sha256((code + "\n" + body).encode("utf-8")).hexdigest()
    line = ("<!-- ALMA-SEAL v9 | %s -->" if p.endswith(".html") else "# ALMA-SEAL v9 | %s") % seal
    open(p, "w", encoding="utf-8", newline="\n").write(body + "\n" + line + "\n")
    print("✅  تم ختم:", os.path.basename(p))
print("احتفظ بالرمز. للتحقق لاحقاً شغّل «تحقق-الحماية.py».")
input("\nاضغط Enter للخروج.")
