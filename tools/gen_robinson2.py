import shapefile, json, math, os, sys
from shapely.geometry import Polygon, MultiPolygon, Point, box
from shapely.ops import unary_union

EPS = float(sys.argv[1]) if len(sys.argv) > 1 else 0.25
OUT = sys.argv[2] if len(sys.argv) > 2 else "/tmp/opencode/world_robinson_new.json"

W, H = 1000, 512
MX, MY = 14.0, 16.0

LAT = [0,5,10,15,20,25,30,35,40,45,50,55,60,65,70,75,80,85,90]
XT  = [1.0000,0.9986,0.9954,0.9900,0.9822,0.9730,0.9600,0.9427,0.9216,0.8962,0.8679,0.8350,0.7986,0.7597,0.7186,0.6732,0.6213,0.5722,0.5322]
YT  = [0.0000,0.0620,0.1240,0.1860,0.2480,0.3100,0.3720,0.4340,0.4958,0.5571,0.6176,0.6769,0.7346,0.7903,0.8435,0.8936,0.9394,0.9761,1.0000]

def robinson_raw(lon, lat):
    a = abs(lat)
    if a >= 90:
        xv, yv = XT[-1], YT[-1]
    else:
        i = int(a // 5)
        if a > LAT[i]:
            f = (a - LAT[i]) / 5.0
            xv = XT[i] + (XT[i+1]-XT[i])*f
            yv = YT[i] + (YT[i+1]-YT[i])*f
        else:
            xv, yv = XT[i], YT[i]
    return 0.8487*xv*math.radians(lon), 1.3523*yv*(1.0 if lat >= 0 else -1.0)

def dp(points, eps):
    n = len(points)
    if n <= 3:
        return points
    keep = [False]*n
    keep[0] = keep[-1] = True
    stack = [(0, n-1)]
    while stack:
        s, e = stack.pop()
        ax, ay = points[s]; bx, by = points[e]
        dx, dy = bx-ax, by-ay
        L = math.hypot(dx, dy)
        maxd = -1.0; idx = -1
        for i in range(s+1, e):
            px, py = points[i]
            if L == 0:
                d = math.hypot(px-ax, py-ay)
            else:
                d = abs(dy*(ax-px) - dx*(ay-py)) / L
            if d > maxd:
                maxd = d; idx = i
        if maxd > eps and idx > 0:
            keep[idx] = True
            stack.append((s, idx)); stack.append((idx, e))
    return [p for p, k in zip(points, keep) if k]

r = shapefile.Reader("ne/ne_10m_admin_0_countries.shp")
recs = r.records()
shapes = r.shapes()

def rings_of(shp):
    pts = getattr(shp, 'points', [])
    if not pts:
        return []
    parts = list(getattr(shp, 'parts', [len(pts)])) + [len(pts)]
    out = []
    for k in range(len(parts)-1):
        ring = [[pts[j][0], pts[j][1]] for j in range(parts[k], parts[k+1])]
        if len(ring) >= 3:
            out.append(ring)
    return out

feats = []
for i, (rec, shp) in enumerate(zip(recs, shapes)):
    feats.append({
        "idx": i, "name": (rec.NAME or "").strip(), "admin": (rec.ADMIN or "").strip(),
        "iso2": (rec.ISO_A2 or "").strip().upper(),
        "sov_a3": (rec.SOV_A3 or "").strip().upper(),
        "rings": rings_of(shp),
    })

def norm(s):
    s = s.lower()
    s = (s.replace("\u00e9","e").replace("\u00fc","u").replace("\u00f6","o").replace("\u00df","ss"))
    return "".join(ch for ch in s if ch.isalnum())

iso2_index = {}
name_index = {}
name_exact = {}
for f in feats:
    if len(f["iso2"]) == 2 and f["iso2"].isalpha():
        iso2_index[f["iso2"]] = f
    for key in (f["name"], f["admin"]):
        if key:
            name_index.setdefault(norm(key), f)
    if f["name"]:
        name_exact[f["name"]] = f

db = []
for line in open("/tmp/opencode/db_countries.txt"):
    line = line.rstrip("\n")
    if line:
        iso, name = line.split("\t")
        db.append((iso, name))
db_name = {iso: name for iso, name in db}

EXCLUDE = {"IL"}
OVERRIDE = {"NC": "N. Cyprus"}
# handled via real coastline clips / union below
SPECIAL = {"AB", "OS", "TS", "PS", "GE", "MD"}

def anchor_for(iso, name):
    if iso in OVERRIDE and OVERRIDE[iso] in name_exact:
        return name_exact[OVERRIDE[iso]]
    if iso in iso2_index:
        return iso2_index[iso]
    return name_index.get(norm(name))

countries = {}
report = []
failed = []
counts = {"iso2":0,"name":0,"override":0}
for iso, name in db:
    if iso in EXCLUDE or iso in SPECIAL:
        continue
    anchor = anchor_for(iso, name)
    if anchor is not None:
        rings = anchor["rings"]
        how = "override" if (iso in OVERRIDE) else ("iso2" if (len(anchor["iso2"])==2 and anchor["iso2"]==iso) else "name")
        counts[how] = counts.get(how,0)+1
        report.append(f"  {iso} {name!r} <- NE idx={anchor['idx']} {anchor['name']!r} rings={len(rings)} [{how}]")
    else:
        failed.append((iso, name)); continue
    if not rings:
        failed.append((iso, name+" (empty)")); continue
    countries[iso] = {"name": name, "rings": rings}

# ---------- shapely geometry helpers ----------
def rings_to_geom(rings):
    polys = [Polygon(r) for r in rings if len(r) >= 4]
    if not polys:
        return None
    return MultiPolygon(polys) if len(polys) > 1 else polys[0]

def geom_rings(g):
    if g is None or g.is_empty:
        return []
    polys = list(g.geoms) if g.geom_type == "MultiPolygon" else [g]
    rings = []
    for p in polys:
        if p.is_empty:
            continue
        coords = list(p.exterior.coords)
        if coords[0] == coords[-1]:
            coords = coords[:-1]
        if len(coords) >= 3:
            rings.append([[float(x), float(y)] for (x, y) in coords])
    return rings

def ne_geom(iso2):
    f = iso2_index.get(iso2)
    return rings_to_geom(f["rings"]) if f else None

ge_geom = ne_geom("GE")
md_geom = ne_geom("MD")
il_geom = ne_geom("IL")
ps_geom = ne_geom("PS")

# de facto breakaway regions (used to clip the REAL Natural Earth coastline)
def ne_admin1_polygon(name):
    r1 = shapefile.Reader("ne/ne_10m_admin_1_states_provinces.shp")
    flds = [f[0] for f in r1.fields[1:]]
    for rec, shp in zip(r1.records(), r1.shapes()):
        if str(dict(zip(flds, rec)).get("name", "")).strip() == name:
            pts = shp.points
            parts = list(getattr(shp, 'parts', [len(pts)])) + [len(pts)]
            rings = []
            for k in range(len(parts)-1):
                ring = [(pts[j][0], pts[j][1]) for j in range(parts[k], parts[k+1])]
                if len(ring) >= 3:
                    rings.append(ring)
            rings.sort(key=len, reverse=True)
            return Polygon(rings[0], rings[1:])
    return None

_ab_official = ne_admin1_polygon("Abkhazia")
ab_region = (_ab_official if _ab_official is not None
             else Polygon([(39.9,43.55),(41.0,43.55),(41.2,43.2),(41.3,43.0),(41.4,42.85),(41.5,42.7),(41.55,42.5),(39.9,42.5)]))

def osm_south_ossetia():
    try:
        with open("osm_south_ossetia.geojson") as fh:
            g = json.load(fh)["geometry"]
        rings = g["coordinates"] if g["type"] == "Polygon" else [r for p in g["coordinates"] for r in p]
        rings = [ring for ring in rings if len(ring) >= 3]
        rings.sort(key=len, reverse=True)
        return Polygon(rings[0], rings[1:])
    except Exception as e:
        print("  OS OSM load error:", e)
        return None
_os_official = osm_south_ossetia()
os_region = (_os_official if _os_official is not None
             else Polygon([(43.58,42.72),(43.95,42.74),(44.35,42.62),(44.62,42.45),
                           (44.55,42.15),(44.20,42.02),(43.85,42.05),(43.60,42.28),(43.58,42.55)]))
ts_region = Polygon([(28.10,46.30),(29.70,46.30),(29.70,46.95),(28.10,46.95)])

print("  AB source: official NE admin_1 polygon" if _ab_official is not None else "  AB source: FALLBACK hand-drawn box")
print("  OS source: official OSM de facto boundary (rel 1152717)" if _os_official is not None else "  OS source: FALLBACK hand-drawn box")
ab_geom = ge_geom.intersection(ab_region)
os_geom = ge_geom.intersection(os_region)
ge_geom2 = ge_geom.difference(ab_region).difference(os_region)
ts_geom = md_geom.intersection(ts_region)
md_geom2 = md_geom.difference(ts_region)
ps_union = unary_union([il_geom, ps_geom])  # full pre-1948 Palestine

def setc(iso, g, how):
    rings = geom_rings(g)
    if rings:
        countries[iso] = {"name": db_name[iso], "rings": rings}
        report.append(f"  {iso} {db_name[iso]!r} <- {how} {g.geom_type} rings={len(rings)} pts={sum(len(x) for x in rings)}")
    else:
        failed.append((iso, db_name[iso] + " (special empty)"))

setc("AB", ab_geom, "clip(NE_Georgia)")
setc("OS", os_geom, "clip(NE_Georgia)")
setc("TS", ts_geom, "clip(NE_Moldova)")
setc("GE", ge_geom2, "NE_Georgia-minus-breakaways")
setc("MD", md_geom2, "NE_Moldova-minus-TS")
setc("PS", ps_union, "union(NE_Israel+NE_Palestine)")

aq = next((f for f in feats if f["name"] == "Antarctica" or f["iso2"] == "AQ"), None)
if aq is not None and "AQ" not in countries:
    countries["AQ"] = {"name": "Antarctica", "rings": aq["rings"]}
    report.append(f"  AQ Antarctica <- NE idx={aq['idx']} rings={len(aq['rings'])}")

# ---- Golan Heights: move from Palestine to Syria ----
golan_box = box(35.63, 32.70, 35.99, 33.42)
golan_part = ps_union.intersection(golan_box)
countries["PS"]["rings"] = geom_rings(ps_union.difference(golan_box))
sy_geom = ne_geom("SY")
countries["SY"]["rings"] = geom_rings(unary_union([sy_geom, golan_part]))
report.append(f"  PS <- Golan (area {golan_part.area:.4f}) removed; SY <- Golan added")

# ---- Merge NE sub-national territories into their sovereign countries ----
# Natural Earth stores these as separate features, so they were otherwise dropped.
TERRITORY_PARENT = {
    177:'DK',227:'DK',
    225:'GB',223:'GB',224:'GB',215:'GB',214:'GB',187:'GB',205:'GB',211:'GB',212:'GB',192:'GB',217:'GB',242:'GB',241:'GB',229:'GB',153:'GB',6:'GB',172:'GB',
    37:'FR',190:'FR',193:'FR',194:'FR',209:'FR',234:'FR',252:'FR',
    38:'NL',184:'NL',185:'NL',
    210:'US',208:'US',204:'US',245:'US',247:'US',248:'US',133:'US',
    226:'FI',
    216:'AU',231:'AU',228:'AU',250:'AU',254:'AU',
    232:'NZ',244:'NZ',
    139:'BR',171:'KZ',20:'SO',
}
def merge_territory(parent_iso, terr_idx):
    if parent_iso not in countries:
        return
    terr = rings_to_geom(feats[terr_idx]["rings"])
    if terr is None or terr.is_empty:
        return
    parent_geom = rings_to_geom(countries[parent_iso]["rings"])
    if parent_geom is None or parent_geom.is_empty:
        countries[parent_iso]["rings"] = geom_rings(terr)
    else:
        countries[parent_iso]["rings"] = geom_rings(unary_union([parent_geom, terr]))
    report.append(f"  TERRITORY idx={terr_idx} {feats[terr_idx]['name']!r} -> {parent_iso}")
for ti, parent in sorted(TERRITORY_PARENT.items()):
    merge_territory(parent, ti)

# project + fit
def proj_ring(ring):
    return [robinson_raw(lo, la) for (lo, la) in ring]

all_rings = {iso: [proj_ring(ring) for ring in c["rings"]] for iso, c in countries.items()}
raw_pts = [p for pr in all_rings.values() for ring in pr for p in ring]
xs=[p[0] for p in raw_pts]; ys=[p[1] for p in raw_pts]
xmin,xmax=min(xs),max(xs); ymin,ymax=min(ys),max(ys)
sx=(W-2*MX)/(xmax-xmin); sy=(H-2*MY)/(ymax-ymin); s=min(sx,sy)
offx=(W-s*(xmax-xmin))/2.0; offy=(H-s*(ymax-ymin))/2.0

def to_px(x, y):
    return (offx + (x - xmin)*s, offy + (ymax - y)*s)

def bbox_diag(px):
    xs=[p[0] for p in px]; ys=[p[1] for p in px]
    return math.hypot(max(xs)-min(xs), max(ys)-min(ys))

out = {"width": W, "height": H, "pad": 8, "source": "Natural Earth 10m (public domain) + de facto breakaway clips", "countries": {}}
before_pts = 0
for iso in sorted(countries):
    rings = []
    for ring in all_rings[iso]:
        px = [to_px(x, y) for (x, y) in ring]
        before_pts += len(px)
        d = bbox_diag(px)
        if d >= 5.0 and len(px) > 12:
            simp = dp(px, EPS)
            if len(simp) >= 12:      # never collapse a ring below a usable count
                px = simp
        px = [[round(a, 2), round(b, 2)] for (a, b) in px]
        if len(px) >= 3:
            rings.append(px)
    if rings:
        out["countries"][iso] = {"name": countries[iso]["name"], "rings": rings}

json.dump(out, open(OUT,"w"), separators=(",",":"))

after_pts = sum(len(r) for c in out["countries"].values() for r in c["rings"])
sz = os.path.getsize(OUT)
print(f"EPS={EPS}  ->  {OUT}")
print(f"output countries: {len(out['countries'])}  (DB={len(db)} +AQ, -IL, -0)")
print(f"match: {counts}  special=6  failed={len(failed)}")
if failed:
    print("FAILED:"); [print(f"  {a}: {b}") for a,b in failed]
print(f"points: before DP={before_pts}  after={after_pts}")
print(f"file size: {sz/1024:.1f} KB  ({sz/1024/1024:.2f} MB)")

# overlap sanity: GE/AB/OS and MD/TS should not overlap
def country_geom_px(iso):
    # rebuild in lon/lat from out via inverse not available; just report area-based via shapely on raw
    return None
print("\nSPECIAL RINGS:")
for iso in ["AB","OS","TS","PS","GE","MD"]:
    if iso in out["countries"]:
        c=out["countries"][iso]
        print(f"  {iso}: rings={len(c['rings'])} pts={sum(len(r) for r in c['rings'])}")
    else:
        print(f"  {iso}: MISSING")
print("IL present?", "IL" in out["countries"])
