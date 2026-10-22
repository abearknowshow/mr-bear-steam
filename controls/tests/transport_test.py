"""Loopback-only fake bridge with a scripted answer per status request. Never binds to or sends to a LAN address.
Script: ok (honour the range), wrong (answer a 1024-byte range, as when the bridge mixes up two readers), drop (silence)."""
import re,socket,threading,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
status=bytes((ROOT/'tests/status-fixture.bin').read_bytes())
plan=['ok','wrong','ok','ok','drop','ok','drop'];reads=[];senders=[]
def run(sock):
 while True:
  try:packet,addr=sock.recvfrom(4096)
  except OSError:return
  if addr[0]!='127.0.0.1':raise AssertionError('Loopback only')
  src,dst,body=re.fullmatch(rb'<PACKT><SRCCN>([^<>]+)</SRCCN><DESCN>([^<>]+)</DESCN><DATAS>(.*)</DATAS></PACKT>',packet,re.S).groups()
  senders.append((src,addr[1]))
  def reply(value):sock.sendto(b'<PACKT><SRCCN>'+dst+b'</SRCCN><DESCN>'+src+b'</DESCN><DATAS>'+value+b'</DATAS></PACKT>',addr)
  if body.startswith(b'SFILE'):reply(b'FILES,MrSt_C03.xml,MrSt_S03.xml');continue
  assert body.startswith(b'STATU'),body[:5]
  start,length=int.from_bytes(body[6:8],'big'),int.from_bytes(body[8:10],'big')
  step=plan[len(reads)] if len(reads)<len(plan) else 'ok';reads.append((start,length,step))
  if step=='drop':continue
  if step=='wrong':start,length=0,1024
  data=status[start:start+length];chunks=[data[i:i+39] for i in range(0,len(data),39)]
  for i,chunk in enumerate(chunks):reply(b'STATV'+bytes([i,i+1 if i+1<len(chunks) else 0,len(chunk)])+chunk)
with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
 sock.bind(('127.0.0.1',0));threading.Thread(target=run,args=(sock,),daemon=True).start()
 subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',str(ROOT/'build/test-classes'),'local.steam.controls.TransportTest','--fake',str(sock.getsockname()[1])],check=True)
asked=[(s,l) for s,l,_ in reads]
assert asked==[(0,1024),(256,42),(256,42),(256,42),(0,1024),(256,42),(0,1024)],reads
ids={i for i,_ in senders[:-2]};ports={p for _,p in senders}
assert len(ids)==1,ids  # one client identity for every request (the bridge drops 1 in 5 new identities)
assert len(ports)>=len(reads),(len(ports),len(reads))  # a fresh socket per request, so late replies cannot mix in
print(f'One client identity across {len(senders)} requests from {len(ports)} sockets.')
print('Map kept through a wrong-range reply and a failed refresh; no refresh while a command runs.')
