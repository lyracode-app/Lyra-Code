let root, sprite, current='', config={}, task='idle', timer, animation, transition, revision=0, urls={};
const poses=['idle','thinking','executing','approval','completed','error'];
function poseForState(){return config.pose && config.pose!=='auto' ? config.pose : (task==='paused'?'error':poses.includes(task)?task:'idle');}
async function show(pose){
  if(!root || current===pose)return;
  const first=!current, token=++revision;
  current=pose;root.dataset.pose=pose;transition?.cancel();
  const half=(config.transitionMs||320)/2;
  // Never cross-fade two bodies: overlapping different arm poses looks like extra limbs.
  if(!first){
    transition=sprite.animate([{opacity:1},{opacity:0}],{duration:half,fill:'forwards',easing:'ease-in-out'});
    try{await transition.finished;}catch{return;}
  }
  if(token!==revision||!root)return;
  sprite.src=urls[pose];transition?.cancel();
  if(!first)transition=sprite.animate([{opacity:0},{opacity:1}],{duration:half,easing:'ease-in-out'});
}
function feedback(){
  if(config.feedback===false || !root)return;
  animation?.cancel();
  animation=root.animate([{transform:'scale(1)'},{transform:'scale(.97)',offset:.4},{transform:'scale(1)'}],{duration:500,easing:'ease-in-out'});
}
export default {
  async mount(api){
    root=document.createElement('div');root.style.cssText='position:relative;width:100%;height:100%;transform-origin:50% 85%';api.root.append(root);
    urls={}; await Promise.all(poses.map(async p=>{const img=new Image();img.src=api.asset(`assets/${p}.png`);await img.decode();urls[p]=img.src;}));
    sprite=document.createElement('img');sprite.style.cssText='width:100%;height:100%;object-fit:contain';root.append(sprite);
    show('idle'); window.deepseekMaidReady=true;
  },
  onEvent(type,data){
    if(type==='config'){config=data;show(poseForState());}
    if(type==='state'){task=data.state;clearTimeout(timer);show(poseForState());if(task==='completed')timer=setTimeout(()=>{task='idle';show(poseForState());},2400);}
    if(type==='tap'||type==='pointerdown')feedback();
    if(type==='drag'){animation?.cancel();root.style.transform=config.feedback===false?'':'rotate(-3deg)';}
    if(type==='dragend'){root.style.transition='transform 400ms ease-out';root.style.transform='none';}
    if(type==='operationResult'){clearTimeout(timer);show(data.ok?'completed':'error');timer=setTimeout(()=>show(poseForState()),1800);}
  },
  unmount(){clearTimeout(timer);animation?.cancel();transition?.cancel();revision++;root?.remove();root=null;sprite=null;urls={};window.deepseekMaidReady=false;}
};
