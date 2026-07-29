;; The guest half of dev/resources/iface.wit. Exports are named by their full
;; WIT path once `wasm-tools component embed` has seen the world.
(module
  (func (export "local:iface/math@1.2.3#add") (param $a i32) (param $b i32) (result i32)
    (i32.add (local.get $a) (local.get $b)))
  (func (export "top-level") (param $v i32) (result i32)
    (local.get $v)))
