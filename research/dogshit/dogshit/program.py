"""Program model for the Dogshit representation study.

A program reacts to a stream of record writes ("puts"). It has record types (integer fields),
derivations (pure values over the current store, some over revision history) and rules
(guarded effects with an explicit multiplicity). These mirror Trough's semantic substrate
(DESIGN.md: records, revisions, derivations, explicit effects) at toy scale.

Every rendering (natural language, constrained English, JavaScript, reference Python) is
generated from this one AST, so the renderings are semantically equivalent by construction;
tests/ checks that claim with independent executable oracles.

Runtime conventions shared by all representations (stated identically in every prompt):
- event = {"type": T, "id": I, "fields": {f: int}}; an unseen id is created with every field 0
  and then the given fields, revision 1, kind "created"; a seen id has the given fields merged,
  revision + 1, kind "updated" (even if no value changed).
- after each put, rules run in declared order; each sees derivations computed after the put.
- "previous" value of a field is its value before the put (0 on create); "change" = new - previous.
- sum/count/total-revisions over no records are 0; max over no records is 0.
- a rule with multiplicity once_per_record fires at most once per record id; once fires at most
  once in the whole run; every fires each time its guard holds.
- output = {"effects": [[name, *args] in order], "derived": {name: final value}}.
"""

import json
import random

CMPS = (">", ">=", "<", "==")
ARITH = ("+", "-")
MULTS = ("every", "once_per_record", "once")
TRIGGERS = ("created", "updated", "put")

TYPE_POOL = [
    ("order", ["qty", "price", "discount"]),
    ("customer", ["credit", "visits", "rating"]),
    ("delivery", ["crates", "stops", "late"]),
    ("invoice", ["amount", "paid", "reminders"]),
    ("sensor", ["reading", "battery", "alarms"]),
    ("task", ["priority", "effort", "done"]),
]
EFFECT_POOL = ["notify", "bill", "audit", "restock", "alert", "archive", "flag", "escalate"]
DERIV_NAMES = ["total", "tally", "peak", "volume", "backlog", "score", "spread", "load"]


def cmp(a, op, b):
    if op == ">":
        return a > b
    if op == ">=":
        return a >= b
    if op == "<":
        return a < b
    if op == "==":
        return a == b
    raise ValueError(op)


def canon(p):
    return json.dumps(p, sort_keys=True, separators=(",", ":"))


# ---------------------------------------------------------------- reference interpreter

def interpret(p, events):
    """Reference semantics: direct AST interpretation. Returns the canonical output."""
    store = {t["name"]: {} for t in p["types"]}  # type -> id -> {"rev":n, "f":{...}}
    order = {t["name"]: [] for t in p["types"]}  # insertion order of ids
    fields = {t["name"]: t["fields"] for t in p["types"]}
    fired = set()
    effects = []

    def rec_ok(r, conds):
        return all(cmp(r["f"][f], op, c) for f, op, c in conds)

    def derive_all():
        vals = {}
        for d in p["derivations"]:
            k = d["kind"]
            if k == "arith":
                a, b = vals[d["a"]], vals[d["b"]]
                vals[d["name"]] = a + b if d["op"] == "+" else a - b
                continue
            rs = [store[d["type"]][i] for i in order[d["type"]]]
            rs = [r for r in rs if rec_ok(r, d["where"])]
            if k == "sum":
                vals[d["name"]] = sum(r["f"][d["field"]] for r in rs)
            elif k == "count":
                vals[d["name"]] = len(rs)
            elif k == "max":
                vals[d["name"]] = max([r["f"][d["field"]] for r in rs], default=0)
            elif k == "revsum":
                vals[d["name"]] = sum(r["rev"] for r in rs)
            else:
                raise ValueError(k)
        return vals

    for ev in events:
        t, i = ev["type"], ev["id"]
        if i in store[t]:
            r = store[t][i]
            prev = dict(r["f"])
            r["f"].update(ev["fields"])
            r["rev"] += 1
            kind = "updated"
        else:
            prev = {f: 0 for f in fields[t]}
            r = {"rev": 1, "f": dict(prev)}
            r["f"].update(ev["fields"])
            store[t][i] = r
            order[t].append(i)
            kind = "created"
        vals = derive_all()
        for ri, rule in enumerate(p["rules"]):
            if rule["type"] != t:
                continue
            if rule["trigger"] != "put" and rule["trigger"] != kind:
                continue
            ok = True
            for g in rule["guard"]:
                gk = g["kind"]
                if gk == "field":
                    v = r["f"][g["field"]]
                elif gk == "delta":
                    v = r["f"][g["field"]] - prev[g["field"]]
                elif gk == "rev":
                    v = r["rev"]
                elif gk == "deriv":
                    v = vals[g["deriv"]]
                else:
                    raise ValueError(gk)
                if not cmp(v, g["op"], g["value"]):
                    ok = False
                    break
            if not ok:
                continue
            m = rule["mult"]
            key = (ri, i) if m == "once_per_record" else (ri,)
            if m != "every":
                if key in fired:
                    continue
                fired.add(key)
            out = [rule["effect"]]
            for a in rule["args"]:
                ak = a["kind"]
                if ak == "id":
                    out.append(i)
                elif ak == "field":
                    out.append(r["f"][a["field"]])
                elif ak == "delta":
                    out.append(r["f"][a["field"]] - prev[a["field"]])
                elif ak == "rev":
                    out.append(r["rev"])
                elif ak == "deriv":
                    out.append(vals[a["deriv"]])
                else:
                    raise ValueError(ak)
            effects.append(out)
    return {"effects": effects, "derived": derive_all()}


# ---------------------------------------------------------------- generator

def _value_for(rng, op):
    return rng.randint(0, 6) if op != "==" else rng.randint(0, 4)


def gen_program(rng, features=None):
    """Random well-formed program. `features` (optional set) forces construct choices so
    held-out compositions can be sampled deliberately."""
    features = features or set()
    ntypes = 2 if rng.random() < 0.35 else 1
    tps = rng.sample(TYPE_POOL, ntypes)
    types = [{"name": n, "fields": list(fs)} for n, fs in tps]
    dnames = rng.sample(DERIV_NAMES, len(DERIV_NAMES))
    derivs = []
    nbase = rng.randint(1, 2)
    base_kinds = ["sum", "count", "max", "revsum"]
    forced = [k for k in base_kinds if ("d_" + k) in features]
    for j in range(max(nbase, len(forced))):
        k = forced[j] if j < len(forced) else rng.choice(base_kinds)
        t = rng.choice(types)
        d = {"name": dnames.pop(), "kind": k, "type": t["name"], "where": []}
        if k in ("sum", "max"):
            d["field"] = rng.choice(t["fields"])
        if rng.random() < 0.5 or "d_where" in features:
            f = rng.choice(t["fields"])
            op = rng.choice(CMPS)
            d["where"] = [[f, op, _value_for(rng, op)]]
        derivs.append(d)
    if len(derivs) >= 2 and (rng.random() < 0.4 or "d_arith" in features):
        a, b = rng.sample(derivs, 2)
        derivs.append({"name": dnames.pop(), "kind": "arith", "op": rng.choice(ARITH),
                       "a": a["name"], "b": b["name"]})
    effs = rng.sample(EFFECT_POOL, len(EFFECT_POOL))
    rules = []
    nrules = rng.randint(1, 3)
    gkinds = ["field", "delta", "rev", "deriv"]
    for j in range(nrules):
        t = rng.choice(types)
        trig = rng.choice(TRIGGERS)
        guard = []
        want = [k for k in gkinds if ("g_" + k) in features and j == 0]
        n = max(len(want), rng.randint(1, 2))
        kinds = want + [rng.choice(gkinds) for _ in range(n - len(want))]
        used = set()
        for gk in kinds:
            if gk in used:
                continue
            used.add(gk)
            op = rng.choice(CMPS)
            if gk == "field":
                guard.append({"kind": "field", "field": rng.choice(t["fields"]), "op": op,
                              "value": _value_for(rng, op)})
            elif gk == "delta":
                op = rng.choice((">", "<", "==", ">="))
                v = {">": rng.randint(0, 3), ">=": rng.randint(1, 3), "<": rng.randint(-2, 0),
                     "==": 0}[op]
                guard.append({"kind": "delta", "field": rng.choice(t["fields"]), "op": op,
                              "value": v})
            elif gk == "rev":
                op = rng.choice((">", ">=", "=="))
                guard.append({"kind": "rev", "op": op, "value": rng.randint(1, 3)})
            else:
                d = rng.choice(derivs)
                guard.append({"kind": "deriv", "deriv": d["name"], "op": op,
                              "value": rng.randint(0, 12)})
        args = [{"kind": "id"}] if rng.random() < 0.8 else []
        akinds = ["field", "delta", "rev", "deriv"]
        forced_a = [k for k in akinds if ("a_" + k) in features and j == 0]
        for ak in forced_a + [rng.choice(akinds) for _ in range(rng.randint(0, 1))]:
            if ak == "field":
                args.append({"kind": "field", "field": rng.choice(t["fields"])})
            elif ak == "delta":
                args.append({"kind": "delta", "field": rng.choice(t["fields"])})
            elif ak == "rev":
                args.append({"kind": "rev"})
            else:
                args.append({"kind": "deriv", "deriv": rng.choice(derivs)["name"]})
        mult = rng.choice(MULTS)
        forced_m = [m for m in MULTS if ("m_" + m) in features]
        if forced_m and j == 0:
            mult = forced_m[0]
        rules.append({"type": t["name"], "trigger": trig, "guard": guard,
                      "effect": effs.pop(), "args": args, "mult": mult})
    return {"types": types, "derivations": derivs, "rules": rules}


def gen_events(rng, p, n=None):
    n = n or rng.randint(8, 14)
    ids = {}
    evs = []
    for t in p["types"]:
        pre = t["name"][0]
        ids[t["name"]] = [pre + str(k) for k in range(1, 4)]
    for _ in range(n):
        t = rng.choice(p["types"])
        i = rng.choice(ids[t["name"]])
        k = rng.randint(1, len(t["fields"]))
        fs = rng.sample(t["fields"], k)
        evs.append({"type": t["name"], "id": i, "fields": {f: rng.randint(0, 9) for f in fs}})
    return evs


def features_of(p):
    """Construct features present in a program (used for held-out composition splits)."""
    fs = set()
    for d in p["derivations"]:
        fs.add("d_" + d["kind"])
        if d.get("where"):
            fs.add("d_where")
    for r in p["rules"]:
        fs.add("m_" + r["mult"])
        fs.add("t_" + r["trigger"])
        for g in r["guard"]:
            fs.add("g_" + g["kind"])
        for a in r["args"]:
            fs.add("a_" + a["kind"])
    return fs


def pairs_of(p):
    """Per-rule construct pairs (guard kind x multiplicity, guard kind x arg kind,
    trigger x multiplicity): the compositions held out from exemplars."""
    out = set()
    for r in p["rules"]:
        for g in r["guard"]:
            out.add(("g_" + g["kind"], "m_" + r["mult"]))
            for a in r["args"]:
                out.add(("g_" + g["kind"], "a_" + a["kind"]))
        out.add(("t_" + r["trigger"], "m_" + r["mult"]))
    return out
