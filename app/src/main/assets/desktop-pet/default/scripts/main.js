let timer, bubbleTimer, bubble, root, config = {}, state = 'idle';
export default {
  async mount(api) {
    root = api.root;
    const style = document.createElement('link'); style.rel = 'stylesheet'; style.href = api.asset('assets/pet.css'); document.head.append(style);
    root.innerHTML = await (await fetch(api.asset('assets/cat.svg'))).text();
    bubble = document.createElement('div'); bubble.className = 'bubble'; root.append(bubble);
    timer = setInterval(() => {
      if (config.roam && state === 'idle') {
        if (api.state.docked) api.emit('reveal');
        else api.emit('move', {dx: Math.round(Math.random()*12-6), dy: Math.round(Math.random()*8-4)});
      }
    }, 1200);
  },
  onEvent(type, data) {
    if (type === 'config') {
      config = data;
      document.documentElement.style.setProperty('--fur', data.color);
      document.documentElement.style.setProperty('--speed', data.speed + 's');
      root.querySelector('svg').style.animationName = data.motion === '摇摆' ? 'sway' : '';
      root.querySelector('svg').style.animationPlayState = data.motion === '静止' ? 'paused' : 'running';
    }
    if (type === 'state') { state = data.state; document.body.dataset.state = data.state; document.body.dataset.docked = data.docked; }
    if (type === 'reveal') document.body.dataset.docked = 'false';
    if (type === 'dock') document.body.dataset.docked = 'true';
    if (type === 'tap' || type === 'drag') {
      const svg = root.querySelector('svg'); svg.classList.add('tap'); setTimeout(() => svg.classList.remove('tap'), 600);
      if (type === 'tap' && config.greeting) {
        clearTimeout(bubbleTimer); bubble.textContent = config.greeting; bubble.hidden = false;
        bubbleTimer = setTimeout(() => { bubble.hidden = true; }, 1600);
      }
    }
    if (type === 'operationResult' && data.operation === 'uninstall' && data.ok) {
      const svg = root.querySelector('svg'); svg.classList.add('eat'); setTimeout(() => svg.classList.remove('eat'), 900);
    }
  },
  unmount() { clearInterval(timer); clearTimeout(bubbleTimer); root?.replaceChildren(); }
};
