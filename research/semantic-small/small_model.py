#!/usr/bin/env python3
"""Public toy-only frozen CPU inference experiment. No provider credentials."""
import ast,json,time,pathlib,hashlib,sys
import benchmark as b
P=pathlib.Path(__file__).resolve().parent
FIELDS={'value':['literal'],'input':['name'],'equals':['left','right'],'differs':['left','right'],'not':['condition'],'all':['conditions'],'times':['left','right'],'when':['condition','then','otherwise']}
def convert(n):
 if isinstance(n,ast.Expression):return convert(n.body)
 if isinstance(n,ast.Constant):return {'value':{'literal':n.value}}
 if isinstance(n,ast.Name):return {'input':{'name':n.id}}
 if isinstance(n,ast.UnaryOp):return {'not':{'condition':convert(n.operand)}}
 if isinstance(n,ast.BoolOp):return {'all':{'conditions':[convert(x) for x in n.values]}}
 if isinstance(n,ast.Compare):return {('equals' if isinstance(n.ops[0],ast.Eq) else 'differs'):{'left':convert(n.left),'right':convert(n.comparators[0])}}
 if isinstance(n,ast.BinOp):return {'times':{'left':convert(n.left),'right':convert(n.right)}}
 if isinstance(n,ast.IfExp):return {'when':{'condition':convert(n.test),'then':convert(n.body),'otherwise':convert(n.orelse)}}
 raise ValueError('unsupported')
def aligned(x,v):
 if not isinstance(x,dict) or len(x)!=1:raise ValueError('single operation record')
 op,d=next(iter(x.items()))
 if op not in FIELDS or not isinstance(d,dict) or set(d)!=set(FIELDS[op]):raise ValueError('roles')
 ev=lambda z:aligned(z,v)
 if op=='value':return d['literal']
 if op=='input':return v[d['name']]
 if op=='not':return not ev(d['condition'])
 if op=='all':return all(ev(z) for z in d['conditions'])
 if op=='when':return ev(d['then'] if ev(d['condition']) else d['otherwise'])
 l,r=ev(d['left']),ev(d['right'])
 return l==r if op=='equals' else (l!=r if op=='differs' else l*r)
def conventional(s,v):
 tree=ast.parse(s,mode='eval')
 allowed=(ast.Expression,ast.Name,ast.Load,ast.Constant,ast.UnaryOp,ast.Not,ast.BoolOp,ast.And,ast.Compare,ast.Eq,ast.NotEq,ast.BinOp,ast.Mult,ast.IfExp)
 if any(not isinstance(n,allowed) for n in ast.walk(tree)):raise ValueError('outside expression grammar')
 if any(isinstance(n,ast.Compare) and len(n.ops)!=1 for n in ast.walk(tree)):raise ValueError('chained comparison')
 return b.reference(s,v)
def freeze():
 cases=[]
 for i,q,r in b.CASES:
  env=b.environments(r);expected=[b.reference(r,v) for v in env];a=convert(ast.parse(r,mode='eval'))
  assert all(aligned(a,v)==w and conventional(r,v)==w for v,w in zip(env,expected))
  names=sorted({n.id for n in ast.walk(ast.parse(r,mode='eval')) if isinstance(n,ast.Name)})
  shared='Translate the request into one expression. Output ONLY the expression without Markdown or explanation. Inputs: '+', '.join(names)+'. Request: '+q
  prompts={'conventional':'Use a Python expression limited to variable names, literals, ==, !=, not, and, *, and X if C else Y. '+shared,'aligned':'Use a JSON object expression with exactly one operation key and these roles: '+json.dumps(FIELDS)+'. value wraps literal; input reads name; equals/differs compare; not negates; all is conjunction; times multiplies; when selects then if condition true, otherwise otherwise. '+shared}
  cases.append({'id':i,'prompts':prompts,'inputs':env,'expected':expected,'oracle_python':r,'oracle_aligned':a})
 (P/'frozen-small.json').write_text(json.dumps({'cases':cases,'preflight_both_encodings_passed':True,'source_sha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),'model':json.loads((P/'model-metadata.json').read_text()),'generation':{'do_sample':False,'max_new_tokens':192,'cpu_threads':2,'seed':0},'design':'Fixed paired toy development test; strict parser validation, NOT constrained token generation; no examples/corrections; Python familiar syntax versus semantic role records; syntax/length and pretraining-code familiarity confounded.'},indent=2))
def run():
 import torch
 from transformers import AutoTokenizer,AutoModelForCausalLM
 torch.set_num_threads(2);torch.manual_seed(0)
 f=json.loads((P/'frozen-small.json').read_text());meta=f['model'];t=time.time();tok=AutoTokenizer.from_pretrained(meta['id'],revision=meta['sha']);model=AutoModelForCausalLM.from_pretrained(meta['id'],revision=meta['sha'],torch_dtype=torch.float32,trust_remote_code=False);model.eval();rows=[];load=time.time()-t
 for index,c in enumerate(f['cases']):
  # Alternate arm order deterministically; no stochastic settings.
  for arm in (['aligned','conventional'] if index%2==0 else ['conventional','aligned']):
   text=tok.apply_chat_template([{'role':'user','content':c['prompts'][arm]}],tokenize=False,add_generation_prompt=True);enc=tok(text,return_tensors='pt');start=time.time()
   with torch.inference_mode():out=model.generate(**enc,max_new_tokens=192,do_sample=False,pad_token_id=tok.eos_token_id)
   new=out[0][enc['input_ids'].shape[1]:];answer=tok.decode(new,skip_special_tokens=True).strip();valid=False;good=0;error=None
   try:
    program=json.loads(answer) if arm=='aligned' else answer
    for v,w in zip(c['inputs'],c['expected']):
     got=aligned(program,v) if arm=='aligned' else conventional(program,v);good+=type(got)==type(w) and got==w
    valid=True
   except Exception as e:error=str(e)
   rows.append({'case':c['id'],'arm':arm,'text':answer,'syntax_and_execution_valid':valid,'passed':good,'total':len(c['expected']),'exact':good==len(c['expected']) and valid,'error':error,'input_tokens':enc['input_ids'].shape[1],'output_tokens':len(new),'seconds':time.time()-start,'hit_token_limit':len(new)==192})
   (P/'small-results.json').write_text(json.dumps({'model':meta,'load_seconds':load,'rows':rows,'complete':len(rows)==len(f['cases'])*2},indent=2));print(c['id'],arm,'valid',valid,'exact',rows[-1]['exact'],flush=True)
if __name__=='__main__':{'freeze':freeze,'run':run}[sys.argv[1]]()
