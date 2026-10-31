"""Code renderings of a program: reference Python lowering and conventional JavaScript.

These are written independently of program.interpret (different structure: generated
straight-line code per rule) so that agreement between the three is evidence, not tautology.
"""

PY_OP = {">": ">", ">=": ">=", "<": "<", "==": "=="}


def _py_where(where, rv="r"):
    return " and ".join('%s["%s"] %s %d' % (rv, f, PY_OP[op], c) for f, op, c in where)


def render_py(p, fname="run"):
    L = []
    w = L.append
    w("def %s(events):" % fname)
    w("    db = {%s}" % ", ".join('"%s": {}' % t["name"] for t in p["types"]))
    w("    defaults = {%s}" % ", ".join(
        '"%s": {%s}' % (t["name"], ", ".join('"%s": 0' % f for f in t["fields"])) for t in p["types"]))
    w("    fired = set()")
    w("    effects = []")
    w("")
    w("    def derived():")
    w("        v = {}")
    for d in p["derivations"]:
        k = d["kind"]
        if k == "arith":
            w('        v["%s"] = v["%s"] %s v["%s"]' % (d["name"], d["a"], d["op"], d["b"]))
            continue
        cond = (" if " + _py_where(d["where"])) if d["where"] else ""
        src = 'db["%s"].values()' % d["type"]
        if k == "sum":
            w('        v["%s"] = sum(r["%s"] for r in %s%s)' % (d["name"], d["field"], src, cond))
        elif k == "count":
            w('        v["%s"] = sum(1 for r in %s%s)' % (d["name"], src, cond))
        elif k == "max":
            w('        v["%s"] = max([r["%s"] for r in %s%s], default=0)' % (d["name"], d["field"], src, cond))
        elif k == "revsum":
            w('        v["%s"] = sum(r["_rev"] for r in %s%s)' % (d["name"], src, cond))
    w("        return v")
    w("")
    w("    for ev in events:")
    w('        t, i = ev["type"], ev["id"]')
    w("        r = db[t].get(i)")
    w("        created = r is None")
    w("        if created:")
    w('            r = dict(defaults[t], _rev=0)')
    w("            db[t][i] = r")
    w("        prev = dict(r)")
    w('        r.update(ev["fields"])')
    w('        r["_rev"] += 1')
    w("        v = derived()")
    for ri, rule in enumerate(p["rules"]):
        conds = ['t == "%s"' % rule["type"]]
        if rule["trigger"] == "created":
            conds.append("created")
        elif rule["trigger"] == "updated":
            conds.append("not created")
        for g in rule["guard"]:
            gk = g["kind"]
            if gk == "field":
                e = 'r["%s"]' % g["field"]
            elif gk == "delta":
                e = '(r["%s"] - prev["%s"])' % (g["field"], g["field"])
            elif gk == "rev":
                e = 'r["_rev"]'
            else:
                e = 'v["%s"]' % g["deriv"]
            conds.append("%s %s %d" % (e, PY_OP[g["op"]], g["value"]))
        if rule["mult"] == "once_per_record":
            key = '(%d, i)' % ri
            conds.append("%s not in fired" % key)
        elif rule["mult"] == "once":
            key = '(%d,)' % ri
            conds.append("%s not in fired" % key)
        w("        if %s:" % " and ".join(conds))
        if rule["mult"] != "every":
            w("            fired.add(%s)" % key)
        args = []
        for a in rule["args"]:
            ak = a["kind"]
            if ak == "id":
                args.append("i")
            elif ak == "field":
                args.append('r["%s"]' % a["field"])
            elif ak == "delta":
                args.append('r["%s"] - prev["%s"]' % (a["field"], a["field"]))
            elif ak == "rev":
                args.append('r["_rev"]')
            else:
                args.append('v["%s"]' % a["deriv"])
        w('            effects.append(["%s"%s])' % (rule["effect"], "".join(", " + x for x in args)))
    w('    return {"effects": effects, "derived": derived()}')
    return "\n".join(L) + "\n"


JS_OP = {">": ">", ">=": ">=", "<": "<", "==": "==="}


def _js_where(where):
    return " && ".join("r.%s %s %d" % (f, JS_OP[op], c) for f, op, c in where)


def render_js(p, fname="run"):
    """Conventional, self-contained JavaScript implementing the program."""
    L = []
    w = L.append
    w("function %s(events) {" % fname)
    for t in p["types"]:
        w("  const %s = new Map(); // id -> {rev, %s}" % (t["name"] + "s", ", ".join(t["fields"])))
    w("  const fired = new Set();")
    w("  const effects = [];")
    w("")
    w("  function derive() {")
    for d in p["derivations"]:
        k = d["kind"]
        n = d["name"]
        if k == "arith":
            w("    const %s = %s %s %s;" % (n, d["a"], d["op"], d["b"]))
            continue
        src = "[...%s.values()]" % (d["type"] + "s")
        if d["where"]:
            src += ".filter(r => %s)" % _js_where(d["where"])
        if k == "sum":
            w("    const %s = %s.reduce((s, r) => s + r.%s, 0);" % (n, src, d["field"]))
        elif k == "count":
            w("    const %s = %s.length;" % (n, src))
        elif k == "max":
            w("    const %s = %s.reduce((m, r) => Math.max(m, r.%s), 0);" % (n, src, d["field"]))
        elif k == "revsum":
            w("    const %s = %s.reduce((s, r) => s + r.rev, 0);" % (n, src))
    w("    return { %s };" % ", ".join(d["name"] for d in p["derivations"]))
    w("  }")
    w("")
    w("  for (const { type, id, fields } of events) {")
    for ti, t in enumerate(p["types"]):
        kw = "if" if ti == 0 else "} else if"
        w('    %s (type === "%s") {' % (kw, t["name"]))
        m = t["name"] + "s"
        w("      const created = !%s.has(id);" % m)
        w("      if (created) %s.set(id, { rev: 0, %s });" % (m, ", ".join(f + ": 0" for f in t["fields"])))
        w("      const r = %s.get(id);" % m)
        w("      const prev = { ...r };")
        w("      Object.assign(r, fields);")
        w("      r.rev += 1;")
        w("      const d = derive();")
        for ri, rule in enumerate(p["rules"]):
            if rule["type"] != t["name"]:
                continue
            conds = []
            if rule["trigger"] == "created":
                conds.append("created")
            elif rule["trigger"] == "updated":
                conds.append("!created")
            for g in rule["guard"]:
                gk = g["kind"]
                if gk == "field":
                    e = "r.%s" % g["field"]
                elif gk == "delta":
                    e = "(r.%s - prev.%s)" % (g["field"], g["field"])
                elif gk == "rev":
                    e = "r.rev"
                else:
                    e = "d.%s" % g["deriv"]
                conds.append("%s %s %d" % (e, JS_OP[g["op"]], g["value"]))
            if rule["mult"] == "once_per_record":
                key = '"%d:" + id' % ri
                conds.append("!fired.has(%s)" % key)
            elif rule["mult"] == "once":
                key = '"%d"' % ri
                conds.append("!fired.has(%s)" % key)
            w("      if (%s) {" % " && ".join(conds))
            if rule["mult"] != "every":
                w("        fired.add(%s);" % key)
            args = []
            for a in rule["args"]:
                ak = a["kind"]
                if ak == "id":
                    args.append("id")
                elif ak == "field":
                    args.append("r.%s" % a["field"])
                elif ak == "delta":
                    args.append("r.%s - prev.%s" % (a["field"], a["field"]))
                elif ak == "rev":
                    args.append("r.rev")
                else:
                    args.append("d.%s" % a["deriv"])
            w('        effects.push(["%s"%s]);' % (rule["effect"], "".join(", " + x for x in args)))
            w("      }")
    w("    }")
    w("  }")
    w("  return { effects, derived: derive() };")
    w("}")
    return "\n".join(L) + "\n"
