"""Build-time: download the Ruqyah verses into app/src/main/assets/ruqyah_quran.json.
Source 1: api.alquran.cloud (quran-simple). Source 2: fawazahmed0/quran-api via jsDelivr.
The build fails if neither source returns every verse."""
import json, sys, time, urllib.request, os

RANGES = [(1,1,0),(2,1,5),(2,102,102),(2,163,164),(2,255,257),(2,285,286),(3,18,19),
          (7,54,56),(7,117,122),(10,79,82),(20,65,69),(23,115,118),(37,1,10),(46,29,32),
          (55,33,36),(59,21,24),(72,1,9),(109,1,0),(112,1,0),(113,1,0),(114,1,0)]
NAMES = {1:"سورة الفاتحة",2:"سورة البقرة",3:"سورة آل عمران",7:"سورة الأعراف",10:"سورة يونس",20:"سورة طه",
         23:"سورة المؤمنون",37:"سورة الصافات",46:"سورة الأحقاف",55:"سورة الرحمن",59:"سورة الحشر",
         72:"سورة الجن",109:"سورة الكافرون",112:"سورة الإخلاص",113:"سورة الفلق",114:"سورة الناس"}

def get(url):
    for i in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 SahlAdhan-build"})
            return json.load(urllib.request.urlopen(req, timeout=40))
        except Exception as e:
            print("retry", url, e); time.sleep(3 * (i + 1))
    return None

def alquran(s):
    j = get(f"https://api.alquran.cloud/v1/surah/{s}/quran-simple")
    if not j: return None
    return [(a["numberInSurah"], a["text"]) for a in j["data"]["ayahs"]]

def fawaz(s):
    for ed in ("ara-quransimple", "ara-quransimpleclean"):
        j = get(f"https://cdn.jsdelivr.net/gh/fawazahmed0/quran-api@1/editions/{ed}/{s}.json")
        if j: return [(a["verse"], a["text"]) for a in j["chapter"]]
    return None

cache, out = {}, []
for s, a, b in RANGES:
    if s not in cache:
        cache[s] = alquran(s) or fawaz(s)
        if not cache[s]: sys.exit(f"could not download surah {s}")
    ay = cache[s]; last = b or len(ay)
    sel = [(n, t) for n, t in ay if a <= n <= last]
    if len(sel) != last - a + 1: sys.exit(f"missing ayahs {s}:{a}-{last}")
    out.append({"surah": NAMES[s], "from": a, "to": last, "ayahs": [{"n": n, "t": t} for n, t in sel]})
os.makedirs("app/src/main/assets", exist_ok=True)
json.dump(out, open("app/src/main/assets/ruqyah_quran.json", "w", encoding="utf-8"), ensure_ascii=False)
print(sum(len(x["ayahs"]) for x in out), "ayahs saved")
