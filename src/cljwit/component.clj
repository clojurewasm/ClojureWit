(ns cljwit.component
  "S4's guest-side marshalling, first slice (`doc/design/0030`): the
   linear-memory scaffolding a component needs — `memory`,
   `cabi_realloc` as a bump arena with a grow-last-block fast path,
   reset at each export entry — and the echo trampoline whose core
   signature the note pins: a lifted `func(string) -> string` is
   `(param i32 i32) (result i32)`, and the callee *returns* the
   pointer to its (ptr, code-units) pair.

   Order constraint the arena depends on: a trampoline lifts every
   argument out of linear memory into the GC heap *before* its first
   lowering allocation — the entry reset moves the bump pointer under
   the caller's argument bytes, which stay intact only until the next
   allocation. Lifting allocates nothing linear, so lift-all-then-
   lower-all keeps it sound."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def ^:private arena
  ;; Offsets 0..15 stay unused so 0 keeps meaning null. The grow-last
  ;; fast path is what the Canonical ABI's optimistic utf8 transcode
  ;; calls for (0030 §3); realloc of a non-last block copies.
  "  (memory (export \"memory\") 1)
  (global $next (mut i32) (i32.const 16))
  (global $last (mut i32) (i32.const 0))
  (func $ensure (param $end i32)
    (block $ok
      (loop $grow
        (br_if $ok (i32.le_u (local.get $end)
                             (i32.mul (memory.size) (i32.const 65536))))
        (if (i32.eq (memory.grow (i32.const 1)) (i32.const -1))
          (then (unreachable)))
        (br $grow))))
  (func $cabi_realloc (export \"cabi_realloc\")
        (param $old i32) (param $old-size i32) (param $align i32) (param $new-size i32)
        (result i32)
    (local $p i32) (local $i i32)
    ;; grow-last fast path: the newest block resizes in place.
    (if (i32.and (i32.ne (local.get $old) (i32.const 0))
                 (i32.eq (local.get $old) (global.get $last)))
      (then
        (call $ensure (i32.add (local.get $old) (local.get $new-size)))
        (global.set $next (i32.add (local.get $old) (local.get $new-size)))
        (return (local.get $old))))
    (local.set $p (i32.and (i32.add (global.get $next)
                                    (i32.sub (local.get $align) (i32.const 1)))
                           (i32.xor (i32.sub (local.get $align) (i32.const 1))
                                    (i32.const -1))))
    (call $ensure (i32.add (local.get $p) (local.get $new-size)))
    (global.set $next (i32.add (local.get $p) (local.get $new-size)))
    (global.set $last (local.get $p))
    ;; A non-last realloc preserves the old contents, per realloc.
    (if (i32.ne (local.get $old) (i32.const 0))
      (then
        (local.set $i (i32.const 0))
        (block $done
          (loop $copy
            (br_if $done (i32.ge_u (local.get $i)
                                   (select (local.get $old-size) (local.get $new-size)
                                           (i32.lt_u (local.get $old-size) (local.get $new-size)))))
            (i32.store8 (i32.add (local.get $p) (local.get $i))
                        (i32.load8_u (i32.add (local.get $old) (local.get $i))))
            (local.set $i (i32.add (local.get $i) (i32.const 1)))
            (br $copy)))))
    (local.get $p))
  (func $arena-reset
    (global.set $next (i32.const 16))
    (global.set $last (i32.const 0)))
")

(def echo-wat
  "The echo component's core module: utf8 string in, the same bytes
   out — marshalling only, the body is identity on the GC value
   (`0030` §5)."
  (str "(module\n"
       "  (type $Bytes (array (mut i8)))\n"
       arena
       "  (func (export \"echo\") (param $ptr i32) (param $len i32) (result i32)\n"
       "    (local $arr (ref $Bytes)) (local $i i32) (local $dst i32) (local $ret i32)\n"
       "    (call $arena-reset)\n"
       "    ;; lift: caller bytes -> GC array, before any lowering allocation\n"
       "    (local.set $arr (array.new_default $Bytes (local.get $len)))\n"
       "    (local.set $i (i32.const 0))\n"
       "    (block $done1\n"
       "      (loop $l1\n"
       "        (br_if $done1 (i32.ge_u (local.get $i) (local.get $len)))\n"
       "        (array.set $Bytes (local.get $arr) (local.get $i)\n"
       "                   (i32.load8_u (i32.add (local.get $ptr) (local.get $i))))\n"
       "        (local.set $i (i32.add (local.get $i) (i32.const 1)))\n"
       "        (br $l1)))\n"
       "    ;; body: identity\n"
       "    ;; lower: GC array -> fresh arena block\n"
       "    (local.set $dst (call $cabi_realloc (i32.const 0) (i32.const 0)\n"
       "                          (i32.const 1) (array.len (local.get $arr))))\n"
       "    (local.set $i (i32.const 0))\n"
       "    (block $done2\n"
       "      (loop $l2\n"
       "        (br_if $done2 (i32.ge_u (local.get $i) (array.len (local.get $arr))))\n"
       "        (i32.store8 (i32.add (local.get $dst) (local.get $i))\n"
       "                    (array.get_u $Bytes (local.get $arr) (local.get $i)))\n"
       "        (local.set $i (i32.add (local.get $i) (i32.const 1)))\n"
       "        (br $l2)))\n"
       "    ;; the callee RETURNS the retptr to its 4-aligned (ptr, code-units)\n"
       "    ;; pair -- 0030 §4's worked signature; a return-area param here is\n"
       "    ;; zwasm's recorded bug.\n"
       "    (local.set $ret (call $cabi_realloc (i32.const 0) (i32.const 0)\n"
       "                          (i32.const 4) (i32.const 8)))\n"
       "    (i32.store (local.get $ret) (local.get $dst))\n"
       "    (i32.store (i32.add (local.get $ret) (i32.const 4)) (array.len (local.get $arr)))\n"
       "    (local.get $ret)))\n"))

(defn wit
  "The echo world, utf8 declared by the lift (0030 §1 — utf8 is also
   the embed default)."
  []
  (str/join "\n"
            ["package cljwit:s4;"
             ""
             "world echo-world {"
             "  export echo: func(s: string) -> string;"
             "}"]))
