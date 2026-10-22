"""Owner-authorized UI operator. Read a screen before choosing an exact target; never retries input."""
import json,sys
from support_cli import request,screenshot,seen,act,ints
op=sys.argv[1] if len(sys.argv)>1 else 'show'
if op=='frame':print(json.dumps(screenshot(sys.argv[2] if len(sys.argv)>2 else None),indent=2));raise SystemExit()
if op in ('tapxy','hold'):x,y=ints(sys.argv[2:],2);print(json.dumps(act({'op':'tap' if op=='tapxy' else 'long_press','hash':seen()['hash'],'x':x,'y':y})));raise SystemExit()
if op=='tapframe':
 # Frame pixels from the saved screenshot, scaled to full-resolution screen pixels.
 fx,fy=ints(sys.argv[2:],2);f=seen();x,y=fx*f['screenWidth']//f['width'],fy*f['screenHeight']//f['height']
 print(json.dumps({'screen':[x,y]}));print(json.dumps(act({'op':'tap','hash':f['hash'],'x':x,'y':y})));raise SystemExit()
if op=='swipe':x1,y1,x2,y2,ms=ints(sys.argv[2:],5);print(json.dumps(act({'op':'swipe','hash':seen()['hash'],'x1':x1,'y1':y1,'x2':x2,'y2':y2,'ms':ms})));raise SystemExit()
s=request('/session');screen=s.get('screen')
if op=='show':
 print(json.dumps({'age':s.get('screenAge'),'frame':s.get('frame'),'secondsLeft':s.get('secondsLeft'),'last':s.get('last'),'pending':s.get('pending'),'windows':screen.get('windows') if screen else None,'controls':[{k:n[k] for k in ('id','text','description','bounds','clickable','scrollable','enabled','selected','checked')} for n in screen.get('nodes',[]) if n['text'] or n['clickable'] or n['scrollable']] if screen else None},ensure_ascii=False,indent=2));raise SystemExit()
if not s.get('approved') or not screen or s.get('screenAge',99)>4:raise SystemExit('No fresh approved screen')
a={'hash':screen['hash']}
if op in ('tap','click','scroll_forward','scroll_backward'):
 label=sys.argv[2];matches=[n for n in screen['nodes'] if n['text']==label or n['description']==label or n['id']==label]
 if len(matches)!=1 or not matches[0]['enabled'] or matches[0]['password']:raise SystemExit('Target absent, ambiguous, disabled or private')
 n=matches[0];print(json.dumps({'target':n['text'],'bounds':n['bounds']},ensure_ascii=False))
 if op=='tap':
  b=n['bounds'];a.update(op='tap',x=(b[0]+b[2])//2,y=(b[1]+b[3])//2)
 else:a.update(op=op,node=n['id'])
elif op=='url':a.update(op='url',url=sys.argv[2])
elif op in ('back','home','launch_bear','stop'):a['op']=op
else:raise SystemExit('Unsupported operation')
print(json.dumps(request('/action',a)))
