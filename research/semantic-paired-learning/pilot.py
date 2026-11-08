#!/usr/bin/env python3
"""Equal supervised-token paired LoRA pilot, frozen family split, finite behavior."""
import copy,hashlib,itertools,json,math,pathlib,resource,time,sys
import constrained as c
P=pathlib.Path(__file__).resolve().parent
I=lambda n:['input',n];N=lambda p:['not',p];E=lambda a,b:['equal',a,b];A=lambda *p:['all',list(p)]
TRAIN=[('false','Emit the boolean false.',['literal',False]),('enabled','Read enabled without changing it.',I('enabled')),('blocked','Read blocked without changing it.',I('blocked')),('not-enabled','Negate the boolean enabled.',N(I('enabled'))),('not-blocked','Negate the boolean blocked.',N(I('blocked'))),('eq-bool','Compare enabled with blocked for equality.',E(I('enabled'),I('blocked'))),('eq-role','Compare sender with target for equality.',E(I('sender'),I('target')))]
TEST=[('true','gate','Emit the boolean true.',['literal',True],c.BOOL,lambda v:True),('held','gate','Read held without changing it.',I('held'),c.BOOL,lambda v:v['held']),('not-held','gate','Negate the boolean held.',N(I('held')),c.BOOL,lambda v:not v['held']),('negated-conjunction','composition','Accept exactly while held is true and blocked is false.',A(I('held'),N(I('blocked'))),c.BOOL,lambda v:v['held'] and not v['blocked']),('roles-forward','composition','Accept only sender matching target together with receiver not matching target.',A(E(I('sender'),I('target')),N(E(I('receiver'),I('target')))),c.ROLE,lambda v:v['sender']==v['target'] and v['receiver']!=v['target']),('roles-reverse','composition','Accept only receiver matching target together with sender not matching target.',A(E(I('receiver'),I('target')),N(E(I('sender'),I('target')))),c.ROLE,lambda v:v['receiver']==v['target'] and v['sender']!=v['target'])]
def freeze():
 train=[{'id':i,'request':q,'program':p} for i,q,p in TRAIN];test=[]
 for i,stage,q,p,env,oracle in TEST:
  want=[oracle(v) for v in env]
  for a in ['aligned','conventional']:assert all(c.run(c.enc(p,a),v,a)==w for v,w in zip(env,want))
  test.append({'id':i,'stage':stage,'request':q,'program':p,'environments':env,'expected':want})
 assert not ({x['request'] for x in train}&{x['request'] for x in test})
 assert all(x['program'][0]!='all' for x in train)
 f={'model':json.loads((P/'model-metadata.json').read_text()),'train':train,'test':test,'schemas':{a:c.schema(a) for a in ['aligned','conventional']},'config':{'seed':0,'threads':2,'supervised_tokens_per_arm':256,'max_updates_per_arm':32,'max_sequence_tokens':384,'rank':4,'alpha':8,'lr':0.0001,'gate_new_tokens':128,'composition_new_tokens':256},'split':'All conjunction composition and role-reversal combination families withheld; held variable entirely absent from training. Same primitive operator schema/scaffold both arms. No testrequest or target supplied during training. Equality primitive is trained; conjunction primitive described but absent fromtrain. This tests tiny newcompositions not blind externalbenchmark.','source_sha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),'token_budget_rule':'256nonmasked targettokens perarm, fullprogram input retained; finalexample may have only prefix targetlabels active. Nevertruncateinput/target. Updatecounts may differ; report that optimizerstep confound. This is supervised-token matched not context-token matched.'}
 (P/'frozen-pilot.json').write_text(json.dumps(f,indent=2))
def main():
 import torch,jsonschema
 from transformers import AutoTokenizer,AutoModelForCausalLM
 from peft import LoraConfig,get_peft_model,TaskType
 from lmformatenforcer import JsonSchemaParser
 from lmformatenforcer.integrations.transformers import build_transformers_prefix_allowed_tokens_fn
 f=json.loads((P/'frozen-pilot.json').read_text());cfg=f['config'];torch.set_num_threads(cfg['threads']);torch.manual_seed(cfg['seed']);start=time.time();r={'complete':False,'model':f['model'],'stage':'preflight','baseline':[],'arms':{},'config':cfg}
 def save():
  r.update(wall_seconds=time.time()-start,peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss);(P/'pilot-results.json').write_text(json.dumps(r,indent=2))
 save()
 try:
  for arm in ['aligned','conventional']:
   for x in f['train']+f['test']:
    p=c.enc(x['program'],arm);jsonschema.validate(p,f['schemas'][arm]);parser=JsonSchemaParser(f['schemas'][arm])
    for char in json.dumps(p):assert char in parser.get_allowed_characters();parser=parser.add_character(char)
    assert parser.can_end()
  r['preflight_all_programs_schema_decoder_accepted']=True
  meta=f['model'];tok=AutoTokenizer.from_pretrained(meta['id'],revision=meta['sha']);base=AutoModelForCausalLM.from_pretrained(meta['id'],revision=meta['sha'],torch_dtype=torch.float32,trust_remote_code=False);base.config.use_cache=False;base.eval()
  def evaluate(model,arm,tests):
   model.eval();rows=[]
   for x in tests:
    prompt=c.make_prompt(x['request'],arm);chat=tok.apply_chat_template([{'role':'user','content':prompt}],tokenize=False,add_generation_prompt=True);inp=tok(chat,return_tensors='pt');parser=JsonSchemaParser(f['schemas'][arm]);prefix=build_transformers_prefix_allowed_tokens_fn(tok,parser);begin=time.time();cap=cfg['gate_new_tokens'] if x['stage']=='gate' else cfg['composition_new_tokens']
    with torch.inference_mode():out=model.generate(**inp,max_new_tokens=cap,do_sample=False,prefix_allowed_tokens_fn=prefix,pad_token_id=tok.eos_token_id)
    new=out[0][inp['input_ids'].shape[1]:];text=tok.decode(new,skip_special_tokens=True).strip();valid=False;good=0;error=None
    try:
     p=json.loads(text);jsonschema.validate(p,f['schemas'][arm]);valid=True
     for v,w in zip(x['environments'],x['expected']):
      got=c.run(p,v,arm);good+=type(got)==type(w) and got==w
    except Exception as e:error=str(e)[:200]
    rows.append({'id':x['id'],'stage':x['stage'],'text':text,'schema_valid':valid,'exact':valid and good==len(x['expected']),'passed':good,'total':len(x['expected']),'error':error,'input_tokens':inp['input_ids'].shape[1],'output_tokens':len(new),'hit_token_limit':len(new)==cap,'seconds':time.time()-begin});print('eval',arm,x['id'],rows[-1]['exact'],flush=True)
   return rows
  gates=[x for x in f['test'] if x['stage']=='gate'];compositions=[x for x in f['test'] if x['stage']=='composition']
  # Zero-shot competence precedes training; only gate evaluated to avoid test tuning.
  for arm in ['aligned','conventional']:r['baseline'].append({'arm':arm,'gate':evaluate(base,arm,gates)});save()
  model=get_peft_model(base,LoraConfig(task_type=TaskType.CAUSAL_LM,r=cfg['rank'],lora_alpha=cfg['alpha'],lora_dropout=0,target_modules=['q_proj','v_proj'],bias='none'));initial={n:p.detach().clone() for n,p in model.named_parameters() if p.requires_grad};assert all('lora_' in n for n in initial)
  for arm in ['aligned','conventional']:
   for n,p in model.named_parameters():
    if n in initial:p.data.copy_(initial[n])
   torch.manual_seed(cfg['seed']);model.train();opt=torch.optim.AdamW([p for p in model.parameters() if p.requires_grad],lr=cfg['lr']);record={'training':[],'supervised_tokens':0,'full_target_tokens_seen':0,'prompt_tokens_seen':0,'updates':0,'complete_equal_budget':False};r['arms'][arm]=record;budget=cfg['supervised_tokens_per_arm']
   index=0
   while record['supervised_tokens']<budget:
    assert index<cfg['max_updates_per_arm'],'budget cannot fit updatecap';x=f['train'][index%len(f['train'])];prompt=c.make_prompt(x['request'],arm);chat=tok.apply_chat_template([{'role':'user','content':prompt}],tokenize=False,add_generation_prompt=True);pref=tok(chat,add_special_tokens=False)['input_ids'];target=tok(json.dumps(c.enc(x['program'],arm),separators=(',',':'))+tok.eos_token,add_special_tokens=False)['input_ids'];ids=pref+target;assert len(ids)<=cfg['max_sequence_tokens'],'no target truncation';active=min(len(target),budget-record['supervised_tokens']);labels=[-100]*len(pref)+target[:active]+[-100]*(len(target)-active);inp=torch.tensor([ids]);opt.zero_grad(set_to_none=True);begin=time.time();loss=model(input_ids=inp,attention_mask=torch.ones_like(inp),labels=torch.tensor([labels])).loss;assert torch.isfinite(loss);loss.backward();trainable=[p for p in model.parameters() if p.requires_grad];assert all(p.grad is None for p in model.parameters() if not p.requires_grad);assert all(p.grad is None or torch.isfinite(p.grad).all() for p in trainable);norm=float(torch.nn.utils.clip_grad_norm_(trainable,1.0));assert math.isfinite(norm) and norm>0;opt.step();record['training'].append({'id':x['id'],'loss':float(loss.detach()),'active_target_tokens':active,'full_target_tokens':len(target),'prompt_tokens':len(pref),'sequence_tokens':len(ids),'seconds':time.time()-begin,'grad_norm_before_clip':norm,'partial_final_loss':active<len(target)});record['supervised_tokens']+=active;record['full_target_tokens_seen']+=len(target);record['prompt_tokens_seen']+=len(pref);index+=1;record['updates']=index;save()
   record['complete_equal_budget']=record['supervised_tokens']==budget;record['base_gradients_absent']=True;record['post_gate']=evaluate(model,arm,gates);record['post_gate_passed']=all(x['exact'] for x in record['post_gate']);save()
   if record['post_gate_passed']:record['post_composition']=evaluate(model,arm,compositions)
   else:record['post_composition']=[];record['composition_skip_reason']='semantic competence gate failed; not scored as zero'
   save()
  assert all(x['supervised_tokens']==budget for x in r['arms'].values());r.update(complete=True,stage='completed',equal_supervised_token_budget_verified=True,no_loss_based_semantic_claim=True);save()
 except Exception as e:r.update(stage='failed',error_type=type(e).__name__,error=str(e)[:500]);save();raise
if __name__=='__main__':{'freeze':freeze,'run':main}[sys.argv[1]]()
