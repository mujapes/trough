#!/usr/bin/env python3
"""Three optimizer steps ONLY; no semantic improvement claim or paired training."""
import copy,hashlib,json,math,pathlib,resource,time,sys
import constrained as c
P=pathlib.Path(__file__).resolve().parent

def freeze():
 tasks=[('const-false','Return false.',['literal',False]),('read-enabled','Return enabled.',['input','enabled']),('not-blocked','Return the opposite of blocked.',['not',['input','blocked']])]
 train=[{'id':i,'request':q,'program':p,'aligned':c.enc(p,'aligned'),'conventional':c.enc(p,'conventional')} for i,q,p in tasks]
 # Scope separation is explicit; this calibration never evaluates these heldout requests.
 heldout=[{'id':x[0],'family':'role-composition','request':x[2]} for x in c.CASES if x[0] in ['role-forward','role-reverse']]
 assert len(heldout)==2
 data={'scope':'cost calibration only, not training benchmark','train':train,'heldout':heldout,'model':json.loads((P/'model-metadata.json').read_text()),'config':{'steps':3,'threads':2,'rank':4,'alpha':8,'dropout':0,'target_modules':['q_proj','v_proj'],'learning_rate':0.0001,'batch_size':1,'max_sequence_tokens':256,'seed':0},'source_sha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),'guard':'Only3adapteroptimizersteps; no semantic evaluation or fulltraining. Knownprimitive exposure not a heldout accuracy result.'}
 for x in train:
  for arm in ['aligned','conventional']:
   for env in c.BOOL:assert c.run(x[arm],env,arm)==c.run(c.enc(x['program'],'aligned'),env,'aligned')
 (P/'frozen-calibration.json').write_text(json.dumps(data,indent=2))
def main():
 import torch
 from transformers import AutoTokenizer,AutoModelForCausalLM
 from peft import LoraConfig,get_peft_model,TaskType
 f=json.loads((P/'frozen-calibration.json').read_text());cfg=f['config'];torch.set_num_threads(cfg['threads']);torch.manual_seed(cfg['seed']);start=time.time();rows=[];r={'complete':False,'stage':'loading','model':f['model'],'steps':rows}
 def save():
  r['wall_seconds']=time.time()-start;r['peak_rss_kib']=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss;(P/'calibration-results.json').write_text(json.dumps(r,indent=2))
 save()
 try:
  tok=AutoTokenizer.from_pretrained(f['model']['id'],revision=f['model']['sha']);base=AutoModelForCausalLM.from_pretrained(f['model']['id'],revision=f['model']['sha'],torch_dtype=torch.float32,trust_remote_code=False);base.config.use_cache=False
  model=get_peft_model(base,LoraConfig(task_type=TaskType.CAUSAL_LM,r=cfg['rank'],lora_alpha=cfg['alpha'],lora_dropout=0,target_modules=cfg['target_modules'],bias='none'));model.train();trainable=[(n,p) for n,p in model.named_parameters() if p.requires_grad];frozen=[(n,p) for n,p in model.named_parameters() if not p.requires_grad]
  assert trainable and all('lora_' in n for n,p in trainable);assert frozen
  r.update(stage='prepared',load_prepare_seconds=time.time()-start,trainable_parameters=sum(p.numel() for n,p in trainable),total_parameters=sum(p.numel() for p in model.parameters()),trainable_names=[n for n,p in trainable],all_trainable_are_lora=True)
  before=[(n,p.detach().flatten()[:16].clone()) for n,p in frozen];adapters_before={n:p.detach().clone() for n,p in trainable};opt=torch.optim.AdamW([p for n,p in trainable],lr=cfg['learning_rate']);save()
  for index,example in enumerate(f['train']):
   # Cost calibration uses aligned primitive format only; not an arm comparison.
   prompt='Translate to one JSON expression. '+example['request']+'\n';answer=json.dumps(example['aligned'],separators=(',',':'))
   prefix=tok.apply_chat_template([{'role':'user','content':prompt}],tokenize=False,add_generation_prompt=True);pref=tok(prefix,add_special_tokens=False)['input_ids'];target=tok(answer+tok.eos_token,add_special_tokens=False)['input_ids'];ids=pref+target
   assert len(ids)<=cfg['max_sequence_tokens'],'refuse truncated target'
   inp=torch.tensor([ids]);labels=torch.tensor([[-100]*len(pref)+target]);opt.zero_grad(set_to_none=True);step=time.time();out=model(input_ids=inp,attention_mask=torch.ones_like(inp),labels=labels);loss=out.loss
   assert torch.isfinite(loss),'nonfinite loss';loss.backward();assert all(p.grad is None for n,p in frozen),'base gradient'
   grads=[p.grad for n,p in trainable if p.grad is not None];assert grads and all(torch.isfinite(g).all().item() for g in grads),'nonfinite adapter gradients';norm=math.sqrt(sum(float((g.double()**2).sum()) for g in grads));assert norm>0,'zero gradient';torch.nn.utils.clip_grad_norm_([p for n,p in trainable],1.0);opt.step();elapsed=time.time()-step
   delta=sum(float((p.detach()-adapters_before[n]).abs().sum()) for n,p in trainable);assert delta>0
   rows.append({'step':index+1,'id':example['id'],'loss':float(loss.detach()),'loss_is_finite':True,'grad_l2_before_clip':norm,'adapter_delta_l1_from_initial':delta,'seconds_forward_backward_optimizer':elapsed,'sequence_tokens':len(ids),'supervised_target_tokens':len(target),'tokens_per_second':len(ids)/elapsed,'base_grads_absent':True,'all_adapter_grads_finite':True});r['stage']='optimizer_steps';save();print('step',index+1,'seconds',elapsed,'loss',float(loss.detach()),flush=True)
  assert len(rows)==cfg['steps'];assert all(torch.equal(sample,dict(frozen)[n].detach().flatten()[:16]) for n,sample in before),'frozen base sample changed'
  r.update(complete=True,stage='completed',base_frozen_samples_unchanged=True,base_gradient_absence_verified=True,all_trainable_are_lora=True,no_semantic_gain_claim=True,projected_paired_32_updates_seconds=2*32*sum(x['seconds_forward_backward_optimizer'] for x in rows)/len(rows),projection_limit='3single-sequenceprimitive steps only; excludes evaluation/setup and longer-sequence/microbatch effects; extrapolation not measured pairedtraining')
 except Exception as e:
  r.update(stage='failed',error_type=type(e).__name__,error=str(e)[:500]);save();raise
 save()
if __name__=='__main__':{'freeze':freeze,'run':main}[sys.argv[1]]()
