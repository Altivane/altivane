// Altivane v0.8.28 三山五岳静态路线包预计算
// 用法：node tools/fetchRoutes.mjs
// 输出：app/src/main/assets/routes/planned.json
//  - 每条线路起终点（GCJ→WGS84 后）调 OSRM foot demo 拉 OSM 步道路网
//  - 覆盖不到或距离异常 → source=node（回退节点连线，app 内 Catmull-Rom 平滑）
// OSRM demo 限流 1 req/s，全程约 20s
import { writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

// ---- GCJ-02 → WGS84 逆变换（与 RoutesData.kt 同一算法，迭代 5 次精度 <1m）----
const A = 6378245.0, EE = 0.00669342162296594323;
const tLat = (x, y) => -100 + 2*x + 3*y + 0.2*y*y + 0.1*x*y + 0.2*Math.sqrt(Math.abs(x))
  + (20*Math.sin(6*x*Math.PI) + 20*Math.sin(2*x*Math.PI)) * 2/3
  + (20*Math.sin(y*Math.PI) + 40*Math.sin(y/3*Math.PI)) * 2/3
  + (160*Math.sin(y/12*Math.PI) + 320*Math.sin(y*Math.PI/30)) * 2/3;
const tLon = (x, y) => 300 + x + 2*y + 0.1*x*x + 0.1*x*y + 0.1*Math.sqrt(Math.abs(x))
  + (20*Math.sin(6*x*Math.PI) + 20*Math.sin(2*x*Math.PI)) * 2/3
  + (20*Math.sin(x*Math.PI) + 40*Math.sin(x/3*Math.PI)) * 2/3
  + (150*Math.sin(x/12*Math.PI) + 300*Math.sin(x/30*Math.PI)) * 2/3;
function fwd(lat, lon) {
  let dLat = tLat(lon - 105, lat - 35), dLon = tLon(lon - 105, lat - 35);
  const rad = lat / 180 * Math.PI;
  let magic = Math.sin(rad); magic = 1 - EE * magic * magic;
  const sq = Math.sqrt(magic);
  dLat = dLat * 180 / ((A * (1 - EE)) / (magic * sq) * Math.PI);
  dLon = dLon * 180 / (A / sq * Math.cos(rad) * Math.PI);
  return [lat + dLat, lon + dLon];
}
function gcj2wgs(lat, lon) {
  let wLat = lat, wLon = lon;
  for (let i = 0; i < 5; i++) { const g = fwd(wLat, wLon); wLat += lat - g[0]; wLon += lon - g[1]; }
  return [wLat, wLon];
}
const haversine = (a, b) => {
  const R = 6371000, dLat = (b[0]-a[0])*Math.PI/180, dLon = (b[1]-a[1])*Math.PI/180;
  const s = Math.sin(dLat/2)**2 + Math.cos(a[0]*Math.PI/180)*Math.cos(b[0]*Math.PI/180)*Math.sin(dLon/2)**2;
  return 2 * R * Math.asin(Math.sqrt(s));
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ---- 15 条线路起终点（GCJ-02，与 RoutesData.kt SOURCE 一致）----
const LINES = [
  ["泰山", "红门线", [36.2102, 117.1284], [36.2574, 117.1080]],
];

// ---- 拉取 ----
async function fetchRoute(sGCJ, eGCJ) {
  const s = gcj2wgs(sGCJ[0], sGCJ[1]), e = gcj2wgs(eGCJ[0], eGCJ[1]);
  const url = `https://router.project-osrm.org/route/v1/foot/${s[1]},${s[0]};${e[1]},${e[0]}?overview=full&geometries=geojson`;
  const r = await fetch(url, { headers: { "User-Agent": "Altivane/0.8.28 (personal hiking app)" } });
  if (!r.ok) return { source: "node", distKm: 0, points: [s, e] };
  const j = await r.json();
  const route = j.routes?.[0];
  const straight = haversine(s, e);
  // 路网里程 < 直线 1.25 倍（切路）或 > 直线 4.5 倍（绕公路而非登山道）→ 回退节点连线
  if (!route || route.distance < straight * 1.25 || route.distance > straight * 4.5)
    return { source: "node", distKm: 0, points: [s, e] };
  const pts = route.geometry.coordinates.map(c => [c[1], c[0]]);
  return { source: "osrm", distKm: route.distance / 1000, points: pts };
}

// 抽稀：每 3 点取 1，保留首尾（控制 JSON 体积）
const thin = pts => pts.length <= 90
  ? pts
  : pts.filter((p, i) => i % 3 === 0 || i === pts.length - 1);

const out = [];
for (const [route, line, s, e] of LINES) {
  let res;
  try { res = await fetchRoute(s, e); }
  catch (err) { console.error(`${route}/${line} fetch fail: ${err.message}`); res = { source: "node", distKm: 0, points: [gcj2wgs(...s), gcj2wgs(...e)] }; }
  out.push({ route, line, source: res.source, distKm: +res.distKm.toFixed(2), points: thin(res.points) });
  console.log(`${route}/${line}: ${res.source} ${res.distKm ? res.distKm.toFixed(1) + "km" : ""} ${res.points.length}pts`);
  await sleep(1100);
}

const dir = join(root, "app/src/main/assets/routes");
mkdirSync(dir, { recursive: true });
const file = join(dir, "planned.json");
writeFileSync(file, JSON.stringify(out));
console.log(`\nwrote ${file} (${(JSON.stringify(out).length / 1024).toFixed(0)} KB)`);
