let atlas, canvas, ctx, animations, raf, roamTimer, releaseTimer;
let pose = 'idle', started = 0, config = {}, taskState = 'idle', lastDrag, look = 12;
function setPose(next) {
  if (next === pose) return;
  pose = next; started = performance.now(); canvas.dataset.animation = pose;
}
function currentPose() {
  if (config.pose && config.pose !== 'auto') return config.pose;
  return ({thinking:'running',executing:'running',approval:'waiting',completed:'jumping',error:'failed'})[taskState] || 'idle';
}
function feedback(next, milliseconds) {
  clearTimeout(releaseTimer); setPose(next);
  releaseTimer = setTimeout(() => setPose(currentPose()), milliseconds);
}
export default {
  async mount(api) {
    animations = await api.json('data/animations.json');
    atlas = new Image(); atlas.src = api.asset('assets/spritesheet.webp'); await atlas.decode();
    if (atlas.naturalWidth !== 1536 || atlas.naturalHeight !== 2288) throw new Error('Unexpected Minty atlas geometry');
    canvas = document.createElement('canvas'); canvas.width = 192; canvas.height = 208;
    canvas.style.cssText = 'width:100%;height:100%;object-fit:contain'; canvas.dataset.animation = pose;
    api.root.append(canvas); ctx = canvas.getContext('2d'); started = performance.now();
    const draw = now => {
      const a = animations[pose] || animations.idle;
      let row = a.row, frame = Math.floor((now-started)/1000*a.fps*(config.speed || 1)) % a.count;
      if (pose === 'look') {
        const direction = config.pose === 'look' ? Math.floor((now-started)/180) % 16 : look;
        row = 9 + Math.floor(direction/8); frame = direction % 8;
      }
      canvas.dataset.frame = String(frame);
      ctx.clearRect(0,0,192,208); ctx.drawImage(atlas,frame*192,row*208,192,208,0,0,192,208);
      raf = requestAnimationFrame(draw);
    };
    raf = requestAnimationFrame(draw);
    roamTimer = setInterval(() => {
      if (!config.roam || taskState !== 'idle') return;
      if (api.state.docked) api.emit('reveal');
      else api.emit('move',{dx:Math.round(Math.random()*12-6),dy:Math.round(Math.random()*8-4)});
    },1500);
    window.mintyReady = true;
  },
  onEvent(type,data) {
    if (type === 'config') { config = data; setPose(currentPose()); }
    if (type === 'state') { taskState = data.state; setPose(currentPose()); }
    if (type === 'pointerdown') lastDrag = null;
    if (type === 'drag') { setPose(lastDrag && data.x < lastDrag.x ? 'running-left' : 'running-right'); lastDrag = data; }
    if (type === 'dragend' || type === 'reveal') { lastDrag = null; setPose(currentPose()); }
    if (type === 'tap') feedback('waving', 1300);
    if (type === 'dock') { look = data.side === 'left' ? 4 : 12; setPose('look'); }
    if (type === 'operationResult') feedback(data.ok ? 'jumping' : 'failed', 1600);
  },
  unmount() { cancelAnimationFrame(raf); clearInterval(roamTimer); clearTimeout(releaseTimer); canvas?.remove(); window.mintyReady = false; }
};
