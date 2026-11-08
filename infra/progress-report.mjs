#!/usr/bin/env node
// Builds expert-erp-progress.html: a short progress report for the owner and managers, printable to PDF.
// Read from PROGRESS.md (phases, sub-steps, open items), docs/TODO.md (what waits on the owner),
// docs/next-phases.md (the Later list), docs/review-and-test-plan.md (next steps) and the code (page / test counts).
// Run: make progress   (no libraries needed, Node only)
import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const read = p => readFileSync(join(ROOT, p), "utf8");
const OUT = join(ROOT, "expert-erp-progress.html");

// ---------- Hand-kept parts: change these when the project moves on ----------

// The road to go-live; exactly one stage is "now"
const STAGES = [
  { name: "Build the ERP", note: "All planned phases, 1 to Q", state: "done" },
  { name: "Review and test", note: "Menus, every page, every business flow", state: "now" },
  { name: "Owner set-up", note: "Xero, e-mail, couriers, HMRC accounts", state: "next" },
  { name: "Cutover rehearsal", note: "Move a copy of the Cin7 data", state: "next" },
  { name: "Go live", note: "Cin7 switched off", state: "next" },
];

// Business areas, in the order shown
const AREAS = ["Sales", "Purchasing", "Inventory", "Warehouse", "Finance", "Reports", "Integrations", "Platform"];

// One plain-English line per phase of PROGRESS.md, matched on its heading. A new phase without a line is still
// shown, under Platform, with its sub-steps.
const PHASES = [
  [/^Phase 1 /, "Inventory", "Products, warehouses and live stock on hand"],
  [/^Phase 2 /, "Inventory", "Transfers, stock history, low-stock alerts, suppliers, first purchase and sale orders"],
  [/^Phase 3 /, "Platform", "Audit log, user roles, safe stock updates when many people work at once"],
  [/^Invoicing/, "Sales", "Sales and purchase invoices with payments"],
  [/^Phase 4 /, "Platform", "Custom fields and forms", "later"],
  [/^Phase 5 /, "Platform", "One system for many companies: each subscription has its own users and data"],
  [/^Phase 6A/, "Sales", "A reliable order lifecycle: part shipments, cancellations and returns"],
  [/^Automated Testing/, "Platform", "Automatic tests run on every change"],
  [/^Phase 8 /, "Sales", "Customers in the Cin7 layout, imported from Cin7"],
  [/^Phase 6 —/, "Reports", "Dashboard: stock, orders and sales on one screen"],
  [/^Phase 6\.1/, "Reports", "Dashboard widgets from the specification"],
  [/^Phase C /, "Inventory", "Real opening stock loaded from Cin7"],
  [/^Phase D /, "Sales", "Quote, order, ship, invoice and payment, with VAT, backorders and PDFs"],
  [/^Phase E /, "Purchasing", "Purchase orders, receiving, supplier bills, reorder and FIFO stock costs"],
  [/^Phase 7 /, "Integrations", "Shopify: orders in; fulfilment, refunds, prices and stock back out"],
  [/^Phase G /, "Sales", "Price tiers, contract prices, credit control, statements, addresses"],
  [/^Phase H /, "Finance", "Sales and purchase ledgers, VAT return, profit reports, Xero CSV export"],
  [/^Phase I /, "Platform", "Roles, search, reports, document templates, two-step sign-in"],
  [/^Phase J /, "Platform", "Cutover tools: import open orders, go-live check, backups"],
  [/^Phase K /, "Finance", "The ERP's own accounting like Xero: ledger, P&L, balance sheet, bank, MTD VAT"],
  [/^Phase L /, "Integrations", "Xero link through its API (ready; needs the Xero account)"],
  [/^Phase M /, "Integrations", "E-mail documents, statements and reminders (ready; needs the mail account)"],
  [/^Phase N /, "Warehouse", "Bins, pick lists, packing, phone scanner, stock takes"],
  [/^Phase O /, "Warehouse", "Courier booking, labels and tracking (ready; needs the courier accounts)"],
  [/^Phase P /, "Inventory", "Safety certificates per SKU, kits, units of measure, images, landed cost, product lifecycle"],
  [/^Phase Q /, "Sales", "Smaller fixes before cutover: credit hold, bill numbers, costs, roles, PDFs"],
];
const NOT_PHASES = /^(Architecture|Cross-cutting|API surface|What to build next)/;

// ---------- Reading the files ----------

const esc = s => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
// Markdown inline → HTML: links keep their text only, code becomes plain text (the report is not technical)
const inline = s => esc(s)
  .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
  .replace(/`([^`]+)`/g, "$1")
  .replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>")
  .replace(/~~([^~]+)~~/g, "<s>$1</s>")
  .replace(/(^|[\s(])\*([^*]+)\*/g, "$1<i>$2</i>");
const plain = s => s.replace(/\[([^\]]+)\]\([^)]*\)/g, "$1").replace(/[`*~]/g, "");
const cleanTitle = h => plain(h).replace(/[✅🔄🔲]/gu, "").replace(/\s*\([^)]*\)\s*$/, "").replace(/\s+/g, " ").trim();

// Split a markdown file into ## sections: { heading, lines }
function sections(md, level = 2) {
  const mark = "#".repeat(level) + " ";
  const out = [];
  for (const line of md.split("\n")) {
    if (line.startsWith(mark)) out.push({ heading: line.slice(mark.length).trim(), lines: [] });
    else if (out.length) out[out.length - 1].lines.push(line);
  }
  return out;
}
const tasks = lines => lines
  .map(l => l.match(/^\s*- \[( |x)\] (.*)$/))
  .filter(Boolean)
  .map(m => ({ done: m[1] === "x", text: m[2] }));

// Phases
const progress = read("PROGRESS.md");
const phases = sections(progress).filter(s => !NOT_PHASES.test(s.heading)).map(s => {
  const h = s.heading;
  const meta = PHASES.find(([re]) => re.test(h));
  const all = tasks(s.lines);
  const subs = s.lines.filter(l => l.startsWith("### ")).map(l => l.slice(4))
    .filter(t => /✅|🔲|🔄/u.test(t))
    .map(t => ({ title: cleanTitle(t), done: t.includes("✅") }))
    .filter(t => /^[A-Z]?\d+(\.\d+)?[a-z]?\b/.test(t.title));  // numbered steps only (D1, 7.2, N3…)
  let state = h.includes("✅") ? "done" : h.includes("🔄") ? "doing" : h.includes("🔲") ? "todo" : "done";
  if (meta?.[3] === "later") state = "later";
  const name = cleanTitle(h.replace(/\s*\(.*$/, ""));
  return {
    name, area: meta?.[1] ?? "Platform", summary: meta?.[2] ?? "", state, subs,
    done: all.filter(t => t.done).length, open: all.filter(t => !t.done),
  };
});

// TODO.md: open items of a section whose heading starts with the given text
const todo = sections(read("docs/TODO.md"));
const todoOpen = start => tasks(todo.find(s => s.heading.startsWith(start))?.lines ?? []).filter(t => !t.done);
// "**Title** (more): detail" → title + detail
// The detail is cut to its first plain clause: set-up steps (settings names, URLs) stay in docs/TODO.md
function split(text) {
  const m = text.match(/^\*\*(.+?)\*\*[\s:,.—-]*(.*)$/);
  const short = d => {
    let out = d.replace(/^\((phase [^)]*|[^)]*\d{4}-\d\d-\d\d)\)[\s:,]*/i, "")
      .split(/ → |; | — |, then |(?<!e\.g|i\.e)\. |: (?=client ID)/)[0].trim();
    while ((out.match(/\(/g) ?? []).length > (out.match(/\)/g) ?? []).length) out = out.slice(0, out.lastIndexOf("(")).trim();
    out = out.replace(/\? .*$/, "?").replace(/[:,]$/, "");
    if (/APP_[A-Z_]+|https?:\/\//.test(out)) out = "";
    return out.length > 170 ? out.slice(0, 167).replace(/\s+\S*$/, "") + "…" : out;
  };
  return m ? { title: plain(m[1]), detail: short(m[2]) } : { title: "", detail: short(text) };
}

// The Later list from the owner's plan
const nextPhases = existsSync(join(ROOT, "docs/next-phases.md")) ? read("docs/next-phases.md") : "";
const laterText = sections(nextPhases).find(s => s.heading.startsWith("Later"))?.lines.join(" ").trim() ?? "";
const later = laterText.replace(/\.$/, "").split(/;\s*/).map(s => s.trim()).filter(Boolean)
  .map(s => s[0].toUpperCase() + s.slice(1));

// Next steps: the parts of the review plan
const reviewPath = "docs/review-and-test-plan.md";
const review = existsSync(join(ROOT, reviewPath))
  ? sections(read(reviewPath)).filter(s => /^Part \d/.test(s.heading)).map(s => plain(s.heading)) : [];

// Counts from the code
function walk(dir, test, acc = []) {
  if (!existsSync(dir)) return acc;
  for (const f of readdirSync(dir)) {
    if (f === "node_modules" || f.startsWith(".")) continue;
    const p = join(dir, f);
    if (statSync(p).isDirectory()) walk(p, test, acc); else if (test(f)) acc.push(p);
  }
  return acc;
}
const count = (files, re) => files.reduce((n, f) => n + (readFileSync(f, "utf8").match(re) ?? []).length, 0);
const pages = walk(join(ROOT, "inventory-app/app"), f => f === "page.tsx").length;
const backendTests = count(walk(join(ROOT, "inventory-service/src/test"), f => f.endsWith(".java")), /@Test\b/g);
const e2eTests = count(walk(join(ROOT, "e2e-tests/tests"), f => f.endsWith(".spec.ts")), /^\s*test\(/gm);
let commit = "";
try { commit = execSync("git log -1 --format=%h·%cs", { cwd: ROOT }).toString().trim(); } catch { /* not a git checkout */ }

// ---------- Figures ----------

const built = phases.filter(p => p.state !== "later");
const phasesDone = built.filter(p => p.state === "done").length;
const featuresDone = phases.reduce((n, p) => n + p.done, 0);
const openInBuild = built.flatMap(p => p.open.map(t => ({ phase: p.name, ...t })));
const owner = todoOpen("Needs the owner (").map(t => split(t.text));
const ownerOk = todoOpen("Needs the owner's OK").map(t => split(t.text));
const cutover = todoOpen("Decide at cutover").map(t => split(t.text));
const liveTests = todoOpen("Test later with live systems").map(t => split(t.text));
const today = new Date().toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric" });

// ---------- HTML ----------

const STATE_LABEL = { done: "Done", doing: "In progress", todo: "Not started", later: "Later" };
const area = a => phases.filter(p => p.area === a && p.state !== "later");
const item = ({ title, detail }) =>
  `<li>${title ? `<b>${esc(title)}</b>` : ""}${detail ? `<span class="detail">${inline(detail)}</span>` : ""}</li>`;

const stagesHtml = STAGES.map((s, i) => `
  <li class="stage ${s.state}">
    <span class="dot">${s.state === "done" ? "✓" : i + 1}</span>
    <span class="sname">${esc(s.name)}</span>
    <span class="snote">${esc(s.note)}</span>
    ${s.state === "now" ? '<span class="here">We are here</span>' : ""}
  </li>`).join("");

const areasHtml = AREAS.filter(a => area(a).length).map(a => {
  const ps = area(a);
  const feats = ps.map(p => p.summary).filter(Boolean);
  return `
  <article class="area">
    <h3>${esc(a)}</h3>
    <ul>${[...new Set(feats)].map(f => `<li>${esc(f)}</li>`).join("")}</ul>
  </article>`;
}).join("");

const phasesHtml = phases.map(p => `
  <tr class="${p.state}">
    <td class="pname">${esc(p.name)}</td>
    <td>${esc(p.summary)}${p.subs.length ? `<div class="chips">${p.subs.map(s =>
      `<span class="chip${s.done ? "" : " open"}" title="${esc(s.title)}">${esc(s.title.split(" ")[0])}</span>`).join("")}</div>` : ""}</td>
    <td class="parea">${esc(p.area)}</td>
    <td><span class="badge ${p.state}">${STATE_LABEL[p.state]}</span></td>
  </tr>`).join("");

const html = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Expert ERP Progress</title>
<style>
:root{--bg:#f5f6f8;--card:#fff;--text:#1b2230;--muted:#5f6b7d;--line:#e2e6ec;--accent:#1f5fbf;--accentbg:#e8f0fc;
  --done:#17784a;--donebg:#e3f4ea;--now:#b54708;--nowbg:#fff1e0;--later:#6b7280;--laterbg:#eef0f3}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#0f1218;--card:#171c25;--text:#e6e8ec;--muted:#9aa4b4;
  --line:#2a313d;--accent:#78a9ff;--accentbg:#1b2840;--done:#4cc38a;--donebg:#16291f;--now:#f5a524;--nowbg:#2e2313;--later:#9aa4b4;--laterbg:#222833}}
:root[data-theme="dark"]{--bg:#0f1218;--card:#171c25;--text:#e6e8ec;--muted:#9aa4b4;--line:#2a313d;--accent:#78a9ff;--accentbg:#1b2840;
  --done:#4cc38a;--donebg:#16291f;--now:#f5a524;--nowbg:#2e2313;--later:#9aa4b4;--laterbg:#222833}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Arial,sans-serif}
.wrap{max-width:1040px;margin:0 auto;padding:28px 16px 64px}
header{display:flex;flex-wrap:wrap;align-items:flex-end;gap:8px 24px;margin-bottom:22px}
header h1{margin:0;font-size:26px;letter-spacing:-.01em;flex:1 1 auto}
header .meta{color:var(--muted);font-size:13px}
.print{border:1px solid var(--line);background:var(--card);color:var(--text);border-radius:8px;padding:7px 12px;font:inherit;font-size:13px;cursor:pointer}
section{margin:0 0 26px}
h2{font-size:17px;margin:0 0 10px}
h2 small{color:var(--muted);font-weight:500;font-size:13px;margin-left:6px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px 18px}
/* road to go-live */
.stages{list-style:none;margin:0;padding:0;display:grid;grid-template-columns:repeat(5,1fr);gap:10px}
.stage{position:relative;background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 14px 12px;display:flex;flex-direction:column;gap:2px}
.stage .dot{width:26px;height:26px;border-radius:50%;display:grid;place-items:center;font-size:13px;font-weight:700;background:var(--laterbg);color:var(--later);margin-bottom:6px}
.stage.done .dot{background:var(--done);color:#fff}
.stage.now{border-color:var(--now);box-shadow:0 0 0 1px var(--now) inset;background:var(--nowbg)}
.stage.now .dot{background:var(--now);color:#fff}
.sname{font-weight:600}
.snote{color:var(--muted);font-size:13px}
.here{margin-top:6px;font-size:12px;font-weight:700;color:var(--now);text-transform:uppercase;letter-spacing:.04em}
/* numbers */
.stats{display:grid;grid-template-columns:repeat(5,1fr);gap:10px}
.stat{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px}
.stat b{display:block;font-size:26px;line-height:1.2;font-variant-numeric:tabular-nums}
.stat span{color:var(--muted);font-size:13px}
/* areas */
.areas{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:10px}
.area{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px}
.area h3{margin:0 0 6px;font-size:15px;color:var(--accent)}
.area ul{margin:0;padding-left:18px;font-size:13.5px}
.area li{margin:2px 0}
/* lists */
.list{margin:0;padding-left:20px}
.list li{margin:7px 0}
.list .detail{display:block;color:var(--muted);font-size:13px}
.list b+.detail{margin-top:1px}
.two{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.pills{display:flex;flex-wrap:wrap;gap:6px;margin:0;padding:0;list-style:none}
.pills li{background:var(--laterbg);color:var(--text);border-radius:999px;padding:4px 11px;font-size:13px}
ol.steps{margin:0;padding-left:22px}
ol.steps li{margin:5px 0}
/* phases table */
.tablewrap{overflow-x:auto}
table{width:100%;border-collapse:collapse;font-size:13.5px}
th,td{text-align:left;vertical-align:top;padding:8px 10px;border-bottom:1px solid var(--line)}
th{color:var(--muted);font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:.04em}
.pname{font-weight:600;white-space:nowrap}
.parea{color:var(--muted);white-space:nowrap}
.chips{display:flex;flex-wrap:wrap;gap:4px;margin-top:5px}
.chip{font-size:11px;padding:1px 7px;border-radius:999px;background:var(--donebg);color:var(--done);font-weight:600}
.chip.open{background:var(--nowbg);color:var(--now)}
.badge{display:inline-block;font-size:12px;font-weight:600;padding:2px 9px;border-radius:999px;white-space:nowrap}
.badge.done{background:var(--donebg);color:var(--done)}
.badge.doing,.badge.todo{background:var(--nowbg);color:var(--now)}
.badge.later{background:var(--laterbg);color:var(--later)}
details{margin-top:8px}
summary{cursor:pointer;color:var(--accent);font-size:13.5px}
.small{font-size:13px;color:var(--muted)}
footer{color:var(--muted);font-size:12px;margin-top:30px}
@media (max-width:820px){.stages{grid-template-columns:1fr}.stats{grid-template-columns:repeat(2,1fr)}.two{grid-template-columns:1fr}.pname,.parea{white-space:normal}}
@media print{
  :root,:root[data-theme="dark"]{--bg:#fff;--card:#fff;--text:#111;--muted:#555;--line:#d6dae0;--accent:#1f5fbf;--done:#17784a;--donebg:#e3f4ea;--now:#b54708;--nowbg:#fff1e0;--later:#555;--laterbg:#eef0f3}
  @page{size:A4;margin:14mm 12mm}
  th,td{padding:5px 8px}
  body{font-size:11.5px;-webkit-print-color-adjust:exact;print-color-adjust:exact}
  .wrap{padding:0;max-width:none}
  .print,details{display:none}
  .stages{grid-template-columns:repeat(5,1fr)}.stats{grid-template-columns:repeat(5,1fr)}.two{grid-template-columns:1fr 1fr}
  li,.area,.stage,.stat,tr{break-inside:avoid}
  h2{break-after:avoid}
}
</style>
</head>
<body>
<div class="wrap">

<header>
  <h1>Expert ERP: progress report</h1>
  <span class="meta">${esc(today)}${commit ? ` · version ${esc(commit)}` : ""}</span>
  <button class="print" onclick="window.print()">Print / save as PDF</button>
</header>

<section>
  <h2>Road to go-live</h2>
  <ol class="stages">${stagesHtml}</ol>
</section>

<section>
  <div class="stats">
    <div class="stat"><b>${phasesDone} / ${built.length}</b><span>phases built</span></div>
    <div class="stat"><b>${featuresDone}</b><span>features done</span></div>
    <div class="stat"><b>${pages}</b><span>screens</span></div>
    <div class="stat"><b>${backendTests + e2eTests}</b><span>automatic tests (${backendTests} system, ${e2eTests} screen)</span></div>
    <div class="stat"><b>${owner.length + ownerOk.length}</b><span>items waiting on the owner</span></div>
  </div>
</section>

<section>
  <h2>What the ERP does today</h2>
  <div class="areas">${areasHtml}</div>
</section>

${review.length ? `<section>
  <h2>Now: review and test everything</h2>
  <div class="card">
    <p class="small" style="margin-top:0">The building plan is finished. Before new features or cutover, the whole system is checked again. Plan: docs/review-and-test-plan.md.</p>
    <ol class="steps">${review.map(r => `<li>${esc(r.replace(/^Part \d+:\s*/, ""))}</li>`).join("")}</ol>
  </div>
</section>` : ""}

<section>
  <h2>Waiting on the owner <small>${owner.length + ownerOk.length} items</small></h2>
  <div class="card">
    <ol class="list">${owner.map(item).join("")}</ol>
    ${ownerOk.length ? `<h3 class="small" style="margin:14px 0 4px;font-weight:600">Your OK to add a software library</h3>
    <ol class="list">${ownerOk.map(item).join("")}</ol>` : ""}
  </div>
</section>

<section class="two">
  <div class="card">
    <h2>To settle at cutover <small>${cutover.length}</small></h2>
    <ol class="list">${cutover.map(c => `<li>${c.title ? `<b>${esc(c.title)}</b>` : inline(c.detail)}</li>`).join("")}</ol>
  </div>
  <div class="card">
    <h2>To test with the live systems <small>${liveTests.length}</small></h2>
    <ol class="list">${liveTests.map(c => `<li>${c.title ? `<b>${esc(c.title)}</b>` : inline(c.detail)}</li>`).join("")}</ol>
  </div>
</section>

${later.length ? `<section>
  <h2>Later, after go-live</h2>
  <ul class="pills">${later.map(l => `<li>${esc(l)}</li>`).join("")}</ul>
</section>` : ""}

<section class="phases">
  <h2>All phases <small>in the order they were built</small></h2>
  <div class="card tablewrap">
    <table>
      <thead><tr><th>Phase</th><th>What it brought</th><th>Area</th><th>Status</th></tr></thead>
      <tbody>${phasesHtml}</tbody>
    </table>
    ${openInBuild.length ? `<details>
      <summary>${openInBuild.length} small open points inside finished phases</summary>
      <ul class="list">${openInBuild.map(t => `<li><b>${esc(t.phase)}</b><span class="detail">${inline(t.text)}</span></li>`).join("")}</ul>
    </details>` : ""}
  </div>
</section>

<footer>Made from PROGRESS.md and docs/TODO.md by infra/progress-report.mjs (make progress). The full technical log is PROGRESS.md.</footer>
</div>
</body>
</html>
`;

writeFileSync(OUT, html);
console.log(`Wrote ${OUT}: ${phasesDone}/${built.length} phases, ${featuresDone} features, ${owner.length + ownerOk.length} owner items, ${pages} screens, ${backendTests + e2eTests} tests`);
