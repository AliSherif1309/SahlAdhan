"""Build-time: download Ruqyah verses (quran-simple, with diacritics) from api.alquran.cloud
into app/src/main/assets/ruqyah_quran.json. The build fails if anything is missing."""
import json, sys, urllib.request, os

# (surah, first ayah, last ayah) ; last = 0 means whole surah
RANGES = [(1,1,0),(2,1,5),(2,102,102),(2,163,164),(2,255,257),(2,285,286),(3,18,19),
          (7,54,56),(7,117,122),(10,79,82),(20,65,69),(23,115,118),(37,1,10),(46,29,32),
          (55,33,36),(59,21,24),(72,1,9),(109,1,0),(112,1,0),(113,1,0),(114,1,0)]
out, cache = [], {}
for s, a, b in RANGES:
    if s not in cache:
        req = urllib.request.Request(f"https://api.alquran.cloud/v1/surah/{s}/quran-simple",
                                     headers={"User-Agent": "SahlAdhan-build"})
        cache[s] = json.load(urllib.request.urlopen(req, timeout=30))["data"]
    d = cache[s]
    ayahs = d["ayahs"]
    last = b or len(ayahs)
    sel = [x for x in ayahs if a <= x["numberInSurah"] <= last]
    if len(sel) != last - a + 1: sys.exit(f"missing ayahs {s}:{a}-{last}")
    out.append({"surah": d["name"], "from": a, "to": last,
                "ayahs": [{"n": x["numberInSurah"], "t": x["text"]} for x in sel]})
os.makedirs("app/src/main/assets", exist_ok=True)
json.dump(out, open("app/src/main/assets/ruqyah_quran.json", "w", encoding="utf-8"), ensure_ascii=False)
print(sum(len(x["ayahs"]) for x in out), "ayahs saved")
