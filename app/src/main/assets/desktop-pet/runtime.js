const boot = window.__petBoot;
delete window.__petBoot;
const listeners = new Set(), disposables = new Set(), pending = [];
let lifecycle, mounted = false, disposed = false, config = {}, state = {state:'idle'};
function asset(path) {
  if (typeof path !== 'string' || !boot.files.includes(path)) throw new Error('Unknown package resource: ' + path);
  return new URL('/' + path.split('/').map(encodeURIComponent).join('/'), location.origin).href;
}
function files(directory = '') {
  const prefix = directory ? directory.replace(/\/$/, '') + '/' : '';
  return boot.files.filter(path => path.startsWith(prefix)).sort((a,b) => a.localeCompare(b, 'en', {numeric:true}));
}
function report(error) {
  console.error('Lyra pet:', error);
  document.getElementById('pet-root').textContent = '🐱';
  document.getElementById('pet-root').style.fontSize = '60px';
}
function createSprite(parent = document.getElementById('pet-root')) {
  const element = document.createElement('img'); element.draggable = false; parent.append(element);
  let timer = null, generation = 0;
  function stop() { generation++; clearTimeout(timer); timer = null; }
  const sprite = {
    element, stop,
    async play(animation) {
      stop(); const token = generation;
      if (typeof animation === 'string') animation = {src: animation};
      const frames = animation.frames || (animation.directory && files(animation.directory).filter(path => /\.png$/i.test(path)));
      if (!frames) { element.src = asset(animation.src); await element.decode(); return; }
      if (!frames.length) throw new Error('Animation has no PNG frames');
      const urls = frames.map(asset);
      const fps = Math.max(1, Math.min(60, Number(animation.fps) || 12));
      let index = 0;
      element.src = urls[0]; await element.decode();
      if (token !== generation || disposed) return;
      function advance() {
        if (token !== generation || disposed) return;
        index++;
        if (index === urls.length) { if (animation.loop === false) { animation.onComplete?.(); return; } index = 0; }
        element.src = urls[index];
        timer = setTimeout(advance, Math.max(16, Number(animation.durations?.[index]) || 1000 / fps));
      }
      timer = setTimeout(advance, Math.max(16, Number(animation.durations?.[0]) || 1000 / fps));
    },
    destroy() { stop(); element.removeAttribute('src'); element.remove(); disposables.delete(sprite.destroy); }
  };
  disposables.add(sprite.destroy); return sprite;
}
function audio(path, {loop = false, volume = 1} = {}) {
  const player = new Audio(asset(path)); player.loop = loop;
  const apply = () => { player.muted = !config.soundEnabled; player.volume = Math.max(0, Math.min(1, (Number(config.volume) || 0) * volume)); };
  const update = ({type}) => { if (type === 'config') apply(); }; listeners.add(update); apply();
  const sound = {
    play: () => { apply(); return player.play(); }, pause: () => player.pause(),
    stop: () => { player.pause(); player.currentTime = 0; },
    destroy: () => { player.pause(); player.removeAttribute('src'); player.load(); listeners.delete(update); disposables.delete(sound.destroy); }
  };
  disposables.add(sound.destroy); return sound;
}
const api = Object.freeze({
  root: document.getElementById('pet-root'), manifest: Object.freeze(boot.manifest), asset, files, createSprite, audio,
  get config() { return config; }, get state() { return state; },
  json: async path => { const response = await fetch(asset(path)); if (!response.ok) throw new Error('Cannot load ' + path); return response.json(); },
  emit: (type, data = {}) => LyraPetHost.postMessage(JSON.stringify({type, data})),
  on: callback => { listeners.add(callback); return () => listeners.delete(callback); }
});
window.lyraPet = api;
function dispatch(detail) {
  try { lifecycle.onEvent(detail.type, detail.data, api); } catch (error) { report(error); }
  for (const listener of listeners) { try { listener(detail); } catch (error) { console.error(error); } }
}
addEventListener('lyrapet', ({detail}) => {
  if (detail.type === 'config') config = Object.freeze(detail.data);
  if (detail.type === 'state') state = Object.freeze(detail.data);
  if (detail.type === 'pointerdown') {
    // Native touch owns the window; scripts still receive interaction feedback.
    document.body.dataset.touched = 'true';
  }
  if (mounted) dispatch(detail); else { if (pending.length >= 64) pending.shift(); pending.push(detail); }
});
function destroy() {
  if (disposed) return; disposed = true;
  try { lifecycle?.unmount?.(api); } finally { for (const stop of [...disposables]) stop(); listeners.clear(); }
}
addEventListener('pagehide', destroy);
window.__lyraDispose = destroy;
try {
  lifecycle = (await import(asset(boot.manifest.entry))).default;
  if (!lifecycle || ['mount','onEvent','unmount'].some(key => typeof lifecycle[key] !== 'function')) throw new Error('Entry must export default { mount, onEvent, unmount }');
  await lifecycle.mount(api); mounted = true;
  for (const event of pending.splice(0)) dispatch(event);
} catch (error) { report(error); }
