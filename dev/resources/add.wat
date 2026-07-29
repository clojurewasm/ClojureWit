;; The artifact the S1 spike calls: a WasmGC core module whose exported
;; signature is scalars only, so per doc/design/0007 it becomes a component
;; with no linear memory at all.
(module
  (rec (type $node (sub (struct (field $v i32)))))
  (func (export "add") (param $a i32) (param $b i32) (result i32)
    (struct.get $node $v
      (struct.new $node (i32.add (local.get $a) (local.get $b))))))
