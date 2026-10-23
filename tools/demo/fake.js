// Demo only: a simulated steam generator and light behind the page's real native bridge API.
// Nothing here talks to equipment. The version label comes from live.js, so it follows each version bump.
(function(){
 const q=new URLSearchParams(location.search);
 const sim={running:q.get('running')==='1',minutes:20,remaining:q.get('running')==='1'?18:20,targetF:115,roomF:96,
  lightOn:q.get('light')!=='0',accent:q.get('accent')||'#FFFFFF',openOnWake:true,busy:false,operation:'',look:null,tick:0};
 const DEMO_MINUTE_MS=Number(q.get('minuteMs')||60000);
 let lastTick=Date.now();
 function advance(){
  const now=Date.now();
  if(sim.running&&now-lastTick>=DEMO_MINUTE_MS){lastTick=now;sim.remaining=Math.max(0,sim.remaining-1);if(!sim.remaining)sim.running=false;}
  if(sim.running&&sim.roomF<sim.targetF&&Math.random()<0.02)sim.roomF++;
 }
 function snapshot(){
  return {on:sim.running,possiblyOn:sim.running,heater:sim.running,paused:false,diagnostic:false,requestedMode:sim.running?1:0,
   targetF:sim.targetF,roomF:sim.roomF,minutes:sim.minutes,remaining:sim.running?sim.remaining:0,maxMinutes:60,minF:80,maxF:120,
   lightOn:sim.lightOn,fault:0,program:0};
 }
 function busyFor(op,ms,done){sim.busy=true;sim.operation=op;setTimeout(()=>{done();sim.busy=false;sim.operation='';},ms);}
 window.SteamNative={
  state(){advance();const now=Date.now();
   return JSON.stringify({cloudReady:true,cloudBusy:false,lightError:'',lightDelivered:true,connected:true,at:now,armed:true,busy:sim.busy,
    operation:sim.operation,error:'',errorAction:'',lightPending:false,guarded:false,lightReport:{on:sim.lightOn,at:now-500,freshUntil:now+600000},
    unsafeStart:false,runtimeBlocked:false,snapshot:snapshot()});},
  command(action,value,minutes){
   if(action==='start')busyFor('start',900,()=>{sim.running=true;sim.targetF=value||sim.targetF;sim.minutes=minutes||sim.minutes;sim.remaining=sim.minutes;lastTick=Date.now();});
   else if(action==='stop')busyFor('stop',500,()=>{sim.running=false;});
   else if(action==='time')busyFor('time',600,()=>{sim.remaining=value;sim.minutes=value;});
   else if(action==='temperature')busyFor('temperature',600,()=>{sim.targetF=value;});
  },
  light(on){sim.lightOn=on;return true;},
  originalApp(){},trace(){},
  settings(){const j={accent:sim.accent,openOnWake:sim.openOnWake};if(sim.look)j.look=sim.look;return JSON.stringify(j);},
  setAccent(hex){sim.accent=hex;return true;},
  setOpenOnWake(on){sim.openOnWake=on;},
  setLook(hue,saturation,brightness,disco){sim.look={hue,saturation,brightness,mode:disco?'disco':'solid'};return true;}
 };
})();
