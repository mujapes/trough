#!/usr/bin/env python3
"""Frozen paired development probe; executes data expressions, never model Python."""
import ast, hashlib, itertools, json, pathlib, subprocess, time
P=pathlib.Path(__file__).resolve().parent
CASES=[
 ('negation','Return energy unless held; while held return zero.','0 if held else energy'),
 ('negation-role','Return energy only while held, unless blocked.','energy if held and not blocked else 0'),
 ('roles','Return true when sender equals target and receiver does not equal target.','sender == target and receiver != target'),
 ('roles-reverse','Return true when receiver equals target and sender does not equal target.','receiver == target and sender != target'),
 ('order','Return true exactly when first is release and second is press.','first == "release" and second == "press"'),
 ('order-three','Return true exactly when first is press, second is release, and third is press.','first == "press" and second == "release" and third == "press"'),
 ('identity','Return true unless label is exactly GLOW, but only while held.','label != "GLOW" and held'),
 ('composition','If sender equals target and first is release, return half the energy unless blocked; otherwise return zero.','energy * 0.5 if sender == target and first == "release" and not blocked else 0')]
OPS={'equals':'eq','differs':'ne','all':'and','not':'not','times':'mul','choose':'if'}
def evaluate(e,v,aligned=False):
 if isinstance(e,list):
  allowed=set(OPS if aligned else OPS.values())|{'var','literal'}
  if not e or e[0] not in allowed:raise ValueError('unknown operator')
  op=OPS.get(e[0],e[0]) if aligned else e[0];a=e[1:]
  expected={'var':1,'literal':1,'eq':2,'ne':2,'not':1,'mul':2,'if':3}
  if (op in expected and len(a)!=expected[op]) or (op=='and' and len(a)<2):raise ValueError('arity')
  if op=='var':return v[a[0]]
  if op=='literal':return a[0]
  if op=='if':return evaluate(a[1] if evaluate(a[0],v,aligned) else a[2],v,aligned)
  x=[evaluate(z,v,aligned) for z in a]
  return {'eq':lambda:x[0]==x[1],'ne':lambda:x[0]!=x[1],'and':lambda:all(x),'not':lambda:not x[0],'mul':lambda:x[0]*x[1]}[op]()
 raise ValueError('expression must be array')
def reference(s,v):
 def go(n):
  if isinstance(n,ast.Expression):return go(n.body)
  if isinstance(n,ast.Name):return v[n.id]
  if isinstance(n,ast.Constant):return n.value
  if isinstance(n,ast.IfExp):return go(n.body) if go(n.test) else go(n.orelse)
  if isinstance(n,ast.BoolOp):return all(go(x) for x in n.values)
  if isinstance(n,ast.UnaryOp) and isinstance(n.op,ast.Not):return not go(n.operand)
  if isinstance(n,ast.BinOp) and isinstance(n.op,ast.Mult):return go(n.left)*go(n.right)
  if isinstance(n,ast.Compare):return go(n.left)==go(n.comparators[0]) if isinstance(n.ops[0],ast.Eq) else go(n.left)!=go(n.comparators[0])
  raise ValueError(type(n).__name__)
 return go(ast.parse(s,mode='eval'))
def environments(src):
 domains={'held':[False,True],'blocked':[False,True],'energy':[0,0.4,1],'sender':['A','B'],'receiver':['A','B'],'target':['A','B'],'first':['press','release','other'],'second':['press','release','other'],'third':['press','release','other'],'label':['GLOW','MOON','glow','']}
 names=sorted({n.id for n in ast.walk(ast.parse(src,mode='eval')) if isinstance(n,ast.Name)})
 return [dict(zip(names,vs)) for vs in itertools.product(*(domains[n] for n in names))]
def prompt(aligned):
 names=OPS if aligned else {v:v for v in OPS.values()}
 spec={k: {'eq':'two values equal','ne':'two values unequal','and':'all conditions true','not':'negation','mul':'multiply','if':'condition, then value, else value'}[v] for k,v in names.items()}
 return 'Translate each request to an executable JSON expression. Return ONLY a JSON object mapping id to expression. Expressions are arrays: ["var",name], ["literal",value], or [operation, operands...]. Operators and meaning: '+json.dumps(spec)+'. No examples or corrections. Preserve roles, order and identity exactly.\n'+json.dumps([{'id':i,'request':s} for i,s,r in CASES])
def main():
 import sys
 if len(sys.argv)>1 and sys.argv[1]=='freeze':
  for arm in ['aligned','conventional']:(P/(arm+'.prompt.txt')).write_text(prompt(arm=='aligned'))
  (P/'frozen.json').write_text(json.dumps({'cases':CASES,'prompts_sha256':{a:hashlib.sha256((P/(a+'.prompt.txt')).read_bytes()).hexdigest() for a in ['aligned','conventional']},'design':'same expression tree, aligned natural semantic operators versus conventional compiler IR mnemonics; no training or examples; both familiar and neither arbitrary cipher; eight author-written heldout compositions; not independent blind test'},indent=2))
 elif len(sys.argv)>1 and sys.argv[1]=='score':
  out={}
  for arm in ['aligned','conventional']:
   p=P/(arm+'.response.json')
   if not p.exists():continue
   raw=json.loads(p.read_text());s=raw.get('text','');s=s.strip();s=s.removeprefix('```json').removeprefix('```').removesuffix('```').strip()
   try:pred=json.loads(s)
   except Exception as e:out[arm]={'parse_error':str(e),'text':s};continue
   rows=[]
   for i,_,ref in CASES:
    tests=environments(ref);ok=0;error=None
    for v in tests:
     try:
      a=evaluate(pred[i],v,arm=='aligned');b=reference(ref,v);ok+=type(a)==type(b) and a==b
     except Exception as e:error=str(e)
    rows.append({'id':i,'passed':ok,'total':len(tests),'exact':ok==len(tests),'error':error})
   out[arm]={'cases':rows,'exact_cases':sum(x['exact'] for x in rows),'usage':raw.get('usage',{}),'route_attempts':raw.get('route_attempts'),'models_reported':raw.get('models_reported'),'model_identity_verified':raw.get('model_identity_verified'),'prompt_bytes':len((P/(arm+'.prompt.txt')).read_bytes()),'corrections':0}
  (P/'results.json').write_text(json.dumps(out,indent=2));print(json.dumps(out,indent=2))
if __name__=='__main__':main()
