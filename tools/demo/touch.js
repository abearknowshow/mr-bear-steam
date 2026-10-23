// Demo only: draw a soft ring where each tap lands, like Android's "show taps".
addEventListener('pointerdown',e=>{if(!window.SHOW_TAPS)return;const d=document.createElement('div');
 d.style.cssText='position:fixed;left:'+(e.clientX-22)+'px;top:'+(e.clientY-22)+'px;width:44px;height:44px;border-radius:50%;background:rgba(255,255,255,.28);border:2px solid rgba(255,255,255,.75);pointer-events:none;z-index:99999;transition:transform .45s ease,opacity .45s ease';
 document.body.appendChild(d);requestAnimationFrame(()=>{d.style.transform='scale(1.6)';d.style.opacity='0';});setTimeout(()=>d.remove(),600);},true);
