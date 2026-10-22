"""Loopback-only device simulation. Never binds to or sends to a LAN address."""
import re,socket,threading,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
status=bytearray((ROOT/'tests/status-fixture.bin').read_bytes())
commands=[];reads=[]
def run(sock):
 while True:
  try:packet,addr=sock.recvfrom(4096)
  except OSError:return
  if addr[0]!='127.0.0.1':raise AssertionError('Loopback only')
  match=re.fullmatch(rb'<PACKT><SRCCN>([^<>]+)</SRCCN><DESCN>([^<>]+)</DESCN><DATAS>(.*)</DATAS></PACKT>',packet,re.S)
  assert match
  src,dst,body=match.groups()
  def reply(value):sock.sendto(b'<PACKT><SRCCN>'+dst+b'</SRCCN><DESCN>'+src+b'</DESCN><DATAS>'+value+b'</DATAS></PACKT>',addr)
  if body.startswith(b'SFILE'):reply(b'FILES,MrSt_C03.xml,MrSt_S03.xml')
  elif body.startswith(b'STATU'):
   # Real bridge can leave stale replies on a reused UDP port, or reorder/duplicate fragments.
   sock.sendto(b'<PACKT><SRCCN>'+dst+b'</SRCCN><DESCN>IOS-stale</DESCN><DATAS>FILES,MrSt_C03.xml,MrSt_S03.xml</DATAS></PACKT>',addr)
   # Honour the requested range in 39-byte fragments, as the owner bridge does (2026-10-21).
   start,length=int.from_bytes(body[6:8],'big'),int.from_bytes(body[8:10],'big');data=status[start:start+length];reads.append((start,length))
   chunks=[data[i:i+39] for i in range(0,len(data),39)]
   for i in list(reversed(range(len(chunks))))+[len(chunks)-1]:
    chunk=chunks[i];reply(b'STATV'+bytes([i,i+1 if i+1<len(chunks) else 0,len(chunk)])+chunk)
  elif body.startswith(b'SPACK'):
   assert body[6]==13 and body[8:11]==bytes([70,3,3])
   pos=int.from_bytes(body[11:13],'big'); value=int.from_bytes(body[13:],'big');commands.append((pos,value))
   assert pos in [256,259,261,264]
   status[pos:pos+len(body)-13]=body[13:]
   # Like the real 2026-10-21 session: Start sets heater and countdown, never bit 269.0.
   if pos==256:
    status[295]=(status[295]&~4)|(4 if value else 0)
    status[293:295]=status[261:263] if value else bytes(2)
   elif pos==261 and status[256]==1 and int.from_bytes(status[293:295],'big')>0:status[293:295]=value.to_bytes(2,'big')
   elif pos==259:status[295]=(status[295]&~16)|(16 if value else 0)
   reply(b'PACKS')
  else:raise AssertionError('Unexpected verb')
with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
 sock.bind(('127.0.0.1',0));threading.Thread(target=run,args=(sock,),daemon=True).start()
 subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',str(ROOT/'build/test-classes'),'local.steam.controls.NetworkTest','--fake',str(sock.getsockname()[1])],check=True)
assert commands==[(264,820),(261,15),(256,1),(261,16),(264,830),(259,1),(256,0)],commands
assert reads[0]==(0,1024) and len(reads)>2 and all(r==(256,42) for r in reads[1:]),reads
print('Exactly seven expected writes reached the loopback fake device; none targeted the LAN.')
print(f'One full map read, then {len(reads)-1} live-block reads (offsets 256-297).')
