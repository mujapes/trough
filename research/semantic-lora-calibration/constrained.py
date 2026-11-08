#!/usr/bin/env python3
"""Preflight recursive grammar, run competence gate, then only if both pass compose."""
import copy,hashlib,itertools,json,pathlib,time,sys
P=pathlib.Path(__file__).resolve().parent
NAMES=['held','blocked','enabled','sender','receiver','target','first','second','third']
ROLES={'literal':['value'],'input':['name'],'not':['condition'],'equal':['left','right'],'all':['conditions']}
MNEMONIC={'literal':'const','input':'var','not':'not','equal':'eq','all':'and'}
def obj(props):return {'type':'object','properties':props,'required':list(props),'additionalProperties':False}
def schema(arm):
 ref={'$ref':'#/definitions/Expr'};nodes=[]
 for op,keys in ROLES.items():
  if op=='literal':args={'type':'boolean'}
  elif op=='input':args={'type':'string','enum':NAMES}
  elif op=='all':args={'type':'array','items':ref,'minItems':2,'maxItems':3}
  else:args=ref
  if arm=='aligned':node=obj({op:obj({k:args for k in keys})})
  else:
   n=2 if op in ['equal'] else 1
   # all takes its condition expressions directly as positional arguments.
   arr={'type':'array','items':args if op in ['literal','input'] else ref,'minItems':2 if op=='all' else n,'maxItems':3 if op=='all' else n}
   node=obj({'op':{'type':'string','enum':[MNEMONIC[op]]},'args':arr})
  nodes.append(node)
 return {'$ref':'#/definitions/Expr','definitions':{'Expr':{'anyOf':nodes}}}
def enc(p,arm):
 op,*a=p
 if arm=='aligned':return {op:{k:(v if op in ['literal','input'] else ([enc(z,arm) for z in v] if op=='all' else enc(v,arm))) for k,v in zip(ROLES[op],a)}}
 return {'op':MNEMONIC[op],'args':a if op in ['literal','input'] else ([enc(z,arm) for z in a[0]] if op=='all' else [enc(z,arm) for z in a])}
def run(x,v,arm):
 if arm=='aligned':
  if not isinstance(x,dict) or len(x)!=1:raise ValueError('single role record')
  op,d=next(iter(x.items()))
  if op not in ROLES or not isinstance(d,dict) or set(d)!=set(ROLES[op]):raise ValueError('roles')
  a=[d[k] for k in ROLES[op]]
 else:
  if not isinstance(x,dict) or set(x)!=set(['op','args']):raise ValueError('op args')
  op={v:k for k,v in MNEMONIC.items()}[x['op']];a=x['args'];a=[a] if op=='all' else a
  if len(a)!=len(ROLES[op]):raise ValueError('arity')
 ev=lambda z:run(z,v,arm)
 if op=='literal':return a[0]
 if op=='input':return v[a[0]]
 if op=='not':return not ev(a[0])
 if op=='equal':return ev(a[0])==ev(a[1])
 if op=='all':return all(ev(z) for z in a[0])
I=lambda n:['input',n];NOT=lambda p:['not',p];EQ=lambda a,b:['equal',a,b];ALL=lambda *p:['all',list(p)]
BOOL=[dict(zip(['held','blocked','enabled'],x)) for x in itertools.product([False,True],repeat=3)]
ROLE=[dict(zip(['sender','receiver','target'],x)) for x in itertools.product(['A','B','C'],repeat=3)]
CASES=[
 ('literal','gate','Return true regardless of inputs.',['literal',True],BOOL,lambda v:True),
 ('identity','gate','Return the value of held.',I('held'),BOOL,lambda v:v['held']),
 ('negation','gate','Return the opposite of held.',NOT(I('held')),BOOL,lambda v:not v['held']),
 ('gating','compose','Return true exactly when held and not blocked.',ALL(I('held'),NOT(I('blocked'))),BOOL,lambda v:v['held'] and not v['blocked']),
 ('role-forward','compose','Return true exactly when sender equals target and receiver differs from target.',ALL(EQ(I('sender'),I('target')),NOT(EQ(I('receiver'),I('target')))),ROLE,lambda v:v['sender']==v['target'] and v['receiver']!=v['target']),
 ('role-reverse','compose','Return true exactly when receiver equals target and sender differs from target.',ALL(EQ(I('receiver'),I('target')),NOT(EQ(I('sender'),I('target')))),ROLE,lambda v:v['receiver']==v['target'] and v['sender']!=v['target'])]
def make_prompt(q,arm):
 examples=[(['literal',False],'Return false.'),(I('enabled'),'Return enabled.'),(NOT(I('blocked')),'Return the opposite of blocked.')]
 if arm=='aligned':grammar='Each expression is {operation:{named roles}}. Operations and roles: '+json.dumps(ROLES)
 else:grammar='Each expression is {"op":mnemonic,"args":[positional arguments]}. const(value),var(name),not(condition),eq(left,right),and(condition1,condition2[,condition3]).'
 return 'Output only one executable JSON expression. '+grammar+' literal/const yields boolean; input/var reads input; not negates; equal/eq compares; all/and is conjunction. Nested expressions compose. Examples: '+json.dumps([{'request':s,'expression':enc(p,arm)} for p,s in examples])+'. Request: '+q

def freeze():
 rows=[]
 for i,stage,q,oracle,env,fn in CASES:
  expected=[fn(v) for v in env]
  for arm in ['aligned','conventional']:assert all(run(enc(oracle,arm),v,arm)==w for v,w in zip(env,expected))
  rows.append({'id':i,'stage':stage,'request':q,'environments':env,'expected':expected,'oracles':{a:enc(oracle,a) for a in ['aligned','conventional']},'prompts':{a:make_prompt(q,a) for a in ['aligned','conventional']}})
 f={'cases':rows,'schemas':{a:schema(a) for a in ['aligned','conventional']},'model':json.loads((P/'model-metadata.json').read_text()),'scorer_sha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),'generation':{'do_sample':False,'max_gate_tokens':128,'max_compose_tokens':256,'seed':0,'cpu_threads':2},'gate':'Both arms must have3/3format-valid AND exact semantics before any compose inference. No retries/corrections.','preflight':'Both arm oracle interpreter checks done; JSONSchema + LMFE character acceptance verified on runner BEFORE loading model.'}
 (P/'frozen.json').write_text(json.dumps(f,indent=2))
def main():
 import torch,jsonschema
 from transformers import AutoTokenizer,AutoModelForCausalLM
 from lmformatenforcer import JsonSchemaParser
 from lmformatenforcer.integrations.transformers import build_transformers_prefix_allowed_tokens_fn
 f=json.loads((P/'frozen.json').read_text());proof=[]
 for arm in ['aligned','conventional']:
  for c in f['cases']:
   oracle=c['oracles'][arm];jsonschema.validate(oracle,f['schemas'][arm]);parser=JsonSchemaParser(f['schemas'][arm])
   for char in json.dumps(oracle):
    assert char in parser.get_allowed_characters(),(arm,c['id'],char)
    parser=parser.add_character(char)
   assert parser.can_end();proof.append({'arm':arm,'case':c['id'],'oracle_schema_and_decoder_accepted':True})
 (P/'preflight-results.json').write_text(json.dumps(proof,indent=2))
 torch.set_num_threads(2);torch.manual_seed(0);m=f['model'];start=time.time();tok=AutoTokenizer.from_pretrained(m['id'],revision=m['sha']);model=AutoModelForCausalLM.from_pretrained(m['id'],revision=m['sha'],torch_dtype=torch.float32,trust_remote_code=False).eval();load=time.time()-start;rows=[]
 def save(complete,gate=None):
  (P/'constrained-results.json').write_text(json.dumps({'complete':complete,'competence_gate_passed':gate,'model':m,'load_seconds':load,'rows':rows,'preflight_verified':True},indent=2))
 for stage in ['gate','compose']:
  if stage=='compose':
   gate=all(r['exact'] for r in rows) and len(rows)==6
   if not gate:save(True,False);print('Competence gate FAILED; composition NOT RUN',flush=True);return
  for idx,c in enumerate([c for c in f['cases'] if c['stage']==stage]):
   for arm in (['aligned','conventional'] if idx%2==0 else ['conventional','aligned']):
    prompt=tok.apply_chat_template([{'role':'user','content':c['prompts'][arm]}],tokenize=False,add_generation_prompt=True);inp=tok(prompt,return_tensors='pt');parser=JsonSchemaParser(f['schemas'][arm]);prefix=build_transformers_prefix_allowed_tokens_fn(tok,parser);start=time.time();cap=128 if stage=='gate' else 256
    with torch.inference_mode():out=model.generate(**inp,do_sample=False,max_new_tokens=cap,prefix_allowed_tokens_fn=prefix,pad_token_id=tok.eos_token_id)
    new=out[0][inp['input_ids'].shape[1]:];text=tok.decode(new,skip_special_tokens=True).strip();valid=False;good=0;error=None
    try:
     program=json.loads(text);jsonschema.validate(program,f['schemas'][arm]);valid=True
     for v,w in zip(c['environments'],c['expected']):
      got=run(program,v,arm);good+=type(got)==type(w) and got==w
    except Exception as e:error=str(e)[:300]
    rows.append({'case':c['id'],'stage':stage,'arm':arm,'text':text,'schema_valid':valid,'exact':valid and good==len(c['expected']),'passed':good,'total':len(c['expected']),'error':error,'input_tokens':inp['input_ids'].shape[1],'output_tokens':len(new),'seconds':time.time()-start,'hit_token_limit':len(new)==cap});save(False);print(c['id'],arm,'valid',valid,'exact',rows[-1]['exact'],flush=True)
 save(True,True)
if __name__=='__main__':{'freeze':freeze,'run':main}[sys.argv[1]]()
