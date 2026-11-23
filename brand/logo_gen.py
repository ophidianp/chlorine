"""Chlorine logo: "chlorine" written as one connected piece with a flat, horizontal pen nib
(thick on the down strokes, hairlines on the joins), swept along smooth Bezier centrelines
and sheared forward 44 degrees. Same technique as the Mouse 33 logo (mouse33/brand/logo_gen.py).

Each stroke is a list of knots in upright coordinates (y points down, baseline at the bottom).
The knots are joined by a Catmull-Rom spline, which is written out as cubic Beziers, and a
W x T rectangular nib is swept along it. Strokes overlap at the joins, so the union is one
polygon."""
import sys, json
import numpy as np
from shapely.geometry import Polygon, MultiPolygon
from shapely.ops import unary_union
from shapely import affinity

H = 27.0
W_NIB, T_NIB = 5.6, 2.2
A, X, B = 1.1, 10.6, 25.9          # ascender top, x-height, baseline (centreline)


def bez(p0, p1, p2, p3, n=40):
    t = np.linspace(0, 1, n)[:, None]
    p0, p1, p2, p3 = map(np.array, (p0, p1, p2, p3))
    return (1-t)**3*p0 + 3*(1-t)**2*t*p1 + 3*(1-t)*t**2*p2 + t**3*p3


def spline(knots, k=1/6):
    """Catmull-Rom through the knots, as a list of cubic Bezier segments."""
    P = [np.array(p, float) for p in knots]
    P = [P[0] + (P[0] - P[1]) * 0] + P + [P[-1]]
    segs = []
    for i in range(1, len(P) - 2):
        p0, p1, p2, p3 = P[i-1], P[i], P[i+1], P[i+2]
        if i == 1: p0 = p1 - (p2 - p1)
        if i == len(P) - 3: p3 = p2 + (p2 - p1)
        segs.append((p1, p1 + (p2 - p0) * k * 1.0, p2 - (p3 - p1) * k * 1.0, p2))
    return segs


def sweep(curves, w=W_NIB, t=T_NIB):
    pts = np.vstack([bez(*c) for c in curves])
    nib = np.array([[-w/2, -t/2], [w/2, -t/2], [w/2, t/2], [-w/2, t/2]])
    return unary_union([Polygon(np.vstack([nib + a, nib + b])).convex_hull for a, b in zip(pts[:-1], pts[1:])])


def to_path(g, nd=2):
    out = []
    for p in (g.geoms if isinstance(g, MultiPolygon) else [g]):
        for ring in [p.exterior, *p.interiors]:
            c = list(ring.coords)[:-1]
            out.append("M" + " L".join(f"{x:.{nd}f},{y:.{nd}f}" for x, y in c) + "Z")
    return "".join(out)


def strokes():
    """The word, stroke by stroke. Cusps (sharp turns) sit between strokes."""
    S = []
    # c, rising out of its bowl straight into the h's ascender loop and down its stem
    S.append([(9.6, 13.4), (7.8, 10.9), (4.6, 11.0), (2.0, 15.6), (1.6, 21.4), (3.8, 25.4), (7.4, 25.6),
              (11.2, 21.2), (14.6, 12.0), (17.2, 4.0), (16.2, A), (13.6, 2.8), (12.0, 10.0), (11.2, 18.4), (10.9, B)])
    # h arch, then up through the l loop, down the l, and up into the o
    S.append([(11.0, 21.6), (12.4, 14.0), (15.0, 10.9), (18.0, 11.4), (19.4, 15.6), (19.2, 21.6), (20.4, 25.2),
              (23.0, 25.7), (25.6, 21.6), (28.6, 11.6), (30.6, 3.6), (29.4, A), (27.0, 2.8), (25.4, 11.2),
              (24.6, 19.6), (25.2, 24.8), (27.6, 25.8), (30.6, 22.8), (33.2, 16.0), (35.6, 11.6), (38.4, X)])
    # o, round counter-clockwise from the top, closing back into the join at x-height
    S.append([(38.4, X), (35.2, 11.4), (32.6, 16.6), (32.4, 22.6), (34.8, 25.7), (38.4, 24.6), (40.6, 19.4),
              (41.0, 13.6), (39.4, 10.9), (37.2, 11.0), (38.8, 12.4), (41.6, 11.4), (44.0, 9.4), (45.6, 7.2)])
    # r: a peak above the x-height, a small notch, then the shoulder and stem, hooking into the i
    S.append([(45.6, 7.2), (46.6, 10.4), (47.8, 12.6)])
    S.append([(47.8, 12.6), (49.4, 11.0), (51.6, 10.8), (52.8, 12.6), (52.6, 17.6), (52.2, 23.4), (53.8, 25.8),
              (56.4, 24.6), (58.4, 18.0), (59.2, X)])
    # i stem, hooking out into the n
    S.append([(59.2, X), (58.8, 17.0), (58.6, 23.4), (60.2, 25.8), (62.6, 24.4), (64.4, 18.0), (65.2, X)])
    # n: first stem
    S.append([(65.2, X), (65.0, 17.0), (64.6, B)])
    # n arch, hook, and the e
    S.append([(64.8, 19.6), (66.2, 13.6), (68.8, 10.9), (71.8, 11.2), (73.2, 15.4), (73.0, 21.6), (74.2, 25.2),
              (76.8, 25.7), (80.0, 22.0), (83.2, 17.2), (85.0, 12.6), (83.2, 10.8), (80.0, 12.2), (78.0, 17.6),
              (78.4, 23.4), (81.2, 25.8), (85.2, 24.8), (88.2, 21.4)])
    return S


def dot():
    """The i's tittle: a short nib stroke."""
    return [(60.4, 4.4), (61.2, 3.0)]


def build(skew=44.0, with_dot=True, mark_only=False, sx=1.7):
    S = strokes()
    if mark_only:      # "Cl" (chlorine's symbol) for the favicon: the c rising into a wide looped l
        S = [[(9.6, 13.4), (7.8, 10.9), (4.6, 11.0), (2.0, 15.6), (1.6, 21.4), (3.8, 25.4), (7.4, 25.6),
              (11.6, 21.0), (16.0, 11.6), (19.6, 3.6), (18.4, A), (15.2, 2.8), (13.0, 10.4), (12.0, 18.4),
              (12.2, 24.0), (14.4, 25.8), (17.4, 24.6)]]
    if with_dot and not mark_only:
        S = S + [dot()]
    S = [[(x * sx, y) for x, y in s] for s in S]
    shapes = [sweep(spline(s)) for s in S]
    g = unary_union(shapes)
    g = affinity.skew(g, xs=-skew, origin=(0, H)).simplify(0.035)
    minx, miny, maxx, maxy = g.bounds
    g = affinity.translate(g, -minx, -miny)
    return g, (maxx - minx, maxy - miny)


if __name__ == "__main__":
    g, (w, h) = build()
    print(json.dumps({"w": round(w, 2), "h": round(h, 2), "parts": len(getattr(g, "geoms", [g])),
                      "holes": sum(len(p.interiors) for p in getattr(g, "geoms", [g]))}))
