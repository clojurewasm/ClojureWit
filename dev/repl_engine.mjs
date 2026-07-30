// The REPL's engine child (doc/design/0029): one long-lived node
// process holding one session — runtime instance, var ledger, and the
// engine-side assembler (0026: binaryen.js, explicit features, never
// Features.All). Speaks one JSON object per line on stdio:
//
//   in:  {"op":"init","runtime":"<wat>"}
//        {"op":"eval","wat":"<wat>","statement":true|false}
//   out: {"tag":"ok"}                          (init)
//        {"tag":"result","value":"<printed>"}  (expression form)
//        {"tag":"done"}                        (statement form, e.g. def)
//        {"tag":"exn","class":<n>}             (uncaught Clojure throw)
//        {"tag":"trap","message":"<engine>"}   (native trap)
//        {"tag":"error","message":"..."}       (broken pipe-side invariant)
//
// corpus/session.mjs is this loop unrolled for one batch; keep their
// classification identical.

import { createInterface } from "node:readline";
import binaryen from "binaryen";

const F = binaryen.Features;
const FEATS = F.GC | F.ReferenceTypes | F.TailCall | F.ExceptionHandling |
              F.BulkMemory | F.Multivalue | F.SignExt | F.NontrappingFPToInt |
              F.MutableGlobals;

const assemble = (wat) => {
  const mod = binaryen.parseText(wat);
  mod.setFeatures(FEATS);
  const bin = mod.emitBinary();
  mod.dispose();
  return bin;
};

let rt = null;
const vars = {};

const reply = (obj) => process.stdout.write(JSON.stringify(obj) + "\n");

const handle = (msg) => {
  if (msg.op === "init") {
    rt = new WebAssembly.Instance(new WebAssembly.Module(assemble(msg.runtime)), {});
    return { tag: "ok" };
  }
  if (msg.op !== "eval") return { tag: "error", message: `unknown op ${msg.op}` };
  if (!rt) return { tag: "error", message: "eval before init" };
  const inst = new WebAssembly.Instance(
    new WebAssembly.Module(assemble(msg.wat)),
    { rt: rt.exports, vars },
  );
  for (const [name, val] of Object.entries(inst.exports)) {
    if (name === "entry") continue;
    // 0028 §2: the first definer owns the canonical global forever; a
    // duplicate export is a compiler bug made loud.
    if (name in vars) return { tag: "error", message: `duplicate var export: ${name}` };
    vars[name] = val;
  }
  try {
    const v = inst.exports.entry();
    return msg.statement ? { tag: "done" } : { tag: "result", value: String(v) };
  } catch (e) {
    const tag = rt.exports["clj-exn"];
    if (e instanceof WebAssembly.Exception && e.is(tag))
      return { tag: "exn", class: Number(rt.exports.exn_class(e.getArg(tag, 0))) };
    if (e instanceof WebAssembly.RuntimeError || e instanceof RangeError)
      return { tag: "trap", message: e.message };
    throw e;
  }
};

const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on("line", (line) => {
  if (!line.trim()) return;
  let out;
  try {
    out = handle(JSON.parse(line));
  } catch (e) {
    out = { tag: "error", message: String(e && e.message ? e.message : e) };
  }
  reply(out);
});
rl.on("close", () => process.exit(0));
