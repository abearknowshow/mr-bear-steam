'use strict';
const form=document.getElementById('signin'),message=document.getElementById('status'),button=document.getElementById('submit');
let watch=null;
form.addEventListener('submit',async event=>{
 event.preventDefault();clearInterval(watch);button.disabled=true;message.textContent='Sending securely to your panel…';
 const code=document.getElementById('code').value;
 try{
  const response=await fetch('/credentials',{method:'POST',headers:{'Content-Type':'application/json','X-Setup-Token':document.querySelector('meta[name="setup-token"]').content},body:JSON.stringify({code,email:document.getElementById('email').value,password:document.getElementById('password').value})});
  document.getElementById('password').value='';
  const result=await response.json();if(!response.ok)throw new Error(result.error);
  message.textContent='Waiting for the panel to finish signing in…';
  watch=setInterval(async()=>{
   try{
    const r=await fetch('/state/'+encodeURIComponent(code),{cache:'no-store'});if(!r.ok)throw new Error();const state=(await r.json()).state;
    if(state==='connected'){message.textContent='Signed in. Your panel is ready.';form.hidden=true;clearInterval(watch);}
    else if(['failed','expired','canceled'].includes(state)){message.textContent='Sign-in did not finish. Check the panel, then tap Retry sign-in.';clearInterval(watch);button.disabled=false;}
   }catch(e){message.textContent='Connection lost. Check the panel before trying again.';clearInterval(watch);button.disabled=false;}
  },1500);
 }catch(e){document.getElementById('password').value='';message.textContent=e.message||'Could not connect. Check the panel code.';button.disabled=false;}
});
