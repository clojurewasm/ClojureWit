/* Can a component describe itself at run time, without its WIT?
 *
 * The answer decides cljwit.host's shape: if a loaded component yields its
 * export names, their parameter names and their types, then the host needs no
 * WIT file and no code generation to marshal a call -- which is also what
 * 0009's dev-time dynamism needs.
 *
 *   bb spike-reflect [component.wasm]
 */
#include <wasmtime.h>
#include <wasmtime/component.h>
#include <stdio.h>
#include <stdlib.h>

/* wasmtime_component_valtype_t's kinds, which are NOT
   wasmtime_component_val_t's kinds: valtype groups the signed integers before
   the unsigned ones (s32 = 3, u32 = 7) while val interleaves them (s32 = 5,
   u32 = 6), and they diverge again above 20. The twelve in between coincide,
   so confusing the two passes most tests. Pinned by
   test/cljwit/valtype_enum_test.clj. */
static const char *VK[] = {
  "bool","s8","s16","s32","s64","u8","u16","u32","u64","f32","f64","char",
  "string","list","record","tuple","variant","enum","option","result","flags",
  "own","borrow","future","stream","error-context","map"
};

static void print_valtype(const wasmtime_component_valtype_t *t) {
  /* The kind is all this probe needs; walking nested types is the host's job. */
  unsigned k = (unsigned)t->kind;
  printf("%s", k < sizeof(VK) / sizeof(*VK) ? VK[k] : "?");
}

static void print_func(const char *name, const wasmtime_component_func_type_t *ft) {
  printf("  %-22s (", name);
  size_t n = wasmtime_component_func_type_param_count(ft);
  for (size_t i = 0; i < n; i++) {
    const char *pn; size_t pl; wasmtime_component_valtype_t pt;
    if (!wasmtime_component_func_type_param_nth(ft, i, &pn, &pl, &pt)) continue;
    printf("%s%.*s: ", i ? ", " : "", (int)pl, pn);
    print_valtype(&pt);
    wasmtime_component_valtype_delete(&pt);
  }
  printf(")");
  wasmtime_component_valtype_t rt;
  if (wasmtime_component_func_type_result(ft, &rt)) {
    printf(" -> "); print_valtype(&rt); wasmtime_component_valtype_delete(&rt);
  }
  if (wasmtime_component_func_type_async(ft)) printf("   [async]");
  printf("\n");
}

int main(int argc, char **argv) {
  const char *path = argc > 1 ? argv[1] : "dev/resources/add.component.wasm";
  wasm_engine_t *eng = wasm_engine_new();
  FILE *fp = fopen(path, "rb");
  if (!fp) { printf("cannot open %s\n", path); return 1; }
  fseek(fp, 0, SEEK_END); long sz = ftell(fp); rewind(fp);
  uint8_t *buf = malloc(sz);
  if (fread(buf, 1, sz, fp) != (size_t)sz) { puts("short read"); return 1; }
  fclose(fp);

  wasmtime_component_t *comp;
  if (wasmtime_component_new(eng, buf, sz, &comp)) { puts("component_new"); return 1; }
  wasmtime_component_type_t *ct = wasmtime_component_type(comp);

  printf("%s\n", path);
  size_t n = wasmtime_component_type_export_count(ct, eng);
  printf("%zu export(s)\n", n);
  for (size_t i = 0; i < n; i++) {
    const char *nm; size_t nl; wasmtime_component_item_t item;
    if (!wasmtime_component_type_export_nth(ct, eng, i, &nm, &nl, &item)) continue;
    char name[256]; snprintf(name, sizeof name, "%.*s", (int)nl, nm);
    if (item.kind == WASMTIME_COMPONENT_ITEM_COMPONENT_FUNC) {
      print_func(name, item.of.component_func);
    } else if (item.kind == WASMTIME_COMPONENT_ITEM_COMPONENT_INSTANCE) {
      /* A WIT interface. Its functions are one level down. */
      printf("  %s/  (interface)\n", name);
      wasmtime_component_instance_type_t *it = item.of.component_instance;
      size_t m = wasmtime_component_instance_type_export_count(it, eng);
      for (size_t j = 0; j < m; j++) {
        const char *in; size_t il; wasmtime_component_item_t sub;
        if (!wasmtime_component_instance_type_export_nth(it, eng, j, &in, &il, &sub)) continue;
        char sname[256]; snprintf(sname, sizeof sname, "  %.*s", (int)il, in);
        if (sub.kind == WASMTIME_COMPONENT_ITEM_COMPONENT_FUNC)
          print_func(sname, sub.of.component_func);
        else
          printf("    %s  (item kind %u)\n", sname, (unsigned)sub.kind);
        wasmtime_component_item_delete(&sub);
      }
    } else {
      printf("  %-22s (item kind %u)\n", name, (unsigned)item.kind);
    }
    wasmtime_component_item_delete(&item);
  }
  return 0;
}
