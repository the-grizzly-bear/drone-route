#!/usr/bin/env python3
\
\
\

import sys, math, sqlite3, urllib.request, concurrent.futures as cf, time

URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

def xy(lat, lon, z):
    n = 2 ** z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)

def main():
    out, s, w, n, e, mz = sys.argv[1], *map(float, sys.argv[2:6]), int(sys.argv[6])
    db = sqlite3.connect(out)
    db.execute("create table if not exists metadata(name text, value text)")
    db.execute("create table if not exists tiles(zoom_level int, tile_column int, tile_row int, tile_data blob)")
    db.execute("create unique index if not exists tile_index on tiles(zoom_level, tile_column, tile_row)")
    db.execute("delete from metadata")
    for k, v in [("name", "terrain"), ("format", "png"), ("encoding", "terrarium"), ("type", "baselayer"),
                 ("minzoom", "0"), ("maxzoom", str(mz)), ("bounds", f"{w},{s},{e},{n}"),
                 ("attribution", "Mapzen/AWS Terrain Tiles (USGS 3DEP, SRTM, ...)")]:
        db.execute("insert into metadata values(?,?)", (k, v))
    db.commit()
    have = set(db.execute("select zoom_level, tile_column, tile_row from tiles"))
    todo = []
    for z in range(0, mz + 1):
        x0, y0 = xy(n, w, z); x1, y1 = xy(s, e, z)
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                if (z, x, (2 ** z) - 1 - y) not in have:
                    todo.append((z, x, y))
    print(f"{len(have)} present, {len(todo)} to fetch", flush=True)

    def get(t):
        z, x, y = t
        for a in range(4):
            try:
                with urllib.request.urlopen(URL.format(z=z, x=x, y=y), timeout=30) as r:
                    return t, r.read()
            except Exception as ex:
                err = ex; time.sleep(2 * (a + 1))
        return t, None

    done = 0
    with cf.ThreadPoolExecutor(8) as ex:
        for (z, x, y), data in ex.map(get, todo):
            done += 1
            if data:
                db.execute("insert or replace into tiles values(?,?,?,?)", (z, x, (2 ** z) - 1 - y, data))
            else:
                print("FAILED", z, x, y, flush=True)
            if done % 500 == 0:
                db.commit(); print(f"{done}/{len(todo)}", flush=True)
    db.commit()
    print("done", flush=True)

if __name__ == "__main__":
    main()
